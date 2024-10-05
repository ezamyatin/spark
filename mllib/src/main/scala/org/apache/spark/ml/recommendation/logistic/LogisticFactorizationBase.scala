/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.ml.recommendation.logistic

import java.util.Random

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import org.apache.commons.lang3.NotImplementedException
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.{HashPartitioner, Partitioner, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.ml.recommendation.logistic.local.{ItemData, Optimizer, Opts}
import org.apache.spark.ml.recommendation.logistic.pair.LongPairMulti
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SaveMode, SQLContext}
import org.apache.spark.storage.StorageLevel

private[ml] object LogisticFactorizationBase {
  private val PART_TABLE_TOTAL_SIZE = 10000000

  private def shuffle(arr: Array[Int], rnd: Random) = {
    var i = 0
    val n = arr.length
    var t = 0
    while (i < n - 1) {
      val j = i + rnd.nextInt(n - i)
      t = arr(j)
      arr(j) = arr(i)
      arr(i) = t
      i += 1
    }
    arr
  }

  def createPartitionTable(numPartitions: Int, rnd: Random): Array[Array[Int]] = {
    val nBuckets = PART_TABLE_TOTAL_SIZE / numPartitions
    val result = Array.fill(nBuckets)((0 until numPartitions).toArray)
    (0 until nBuckets).foreach(i => shuffle(result(i), rnd))
    result
  }

  def hash(i: Long, salt: Int, n: Int): Int = {
    var h = (i.hashCode.toLong << 32) | salt
    h ^= h >>> 33
    h *= 0xff51afd7ed558ccdL
    h ^= h >>> 33
    h *= 0xc4ceb9fe1a85ec53L
    h ^= h >>> 33
    (Math.abs(h) % n).toInt
  }
}

private[ml] abstract class LogisticFactorizationBase[T](
             dotVectorSize: Int = 10,
             negative: Int = 10,
             numIterations: Int = 1,
             learningRate: Double = 0.025,
             minLearningRate: Option[Double] = None,
             numThread: Int = 1,
             numPartitions: Int = 1,
             pow: Double = 0,
             lambda: Double = 0,
             useBias: Boolean = false,
             implicitPrefs: Boolean = true,
             seed: Long = 0,
             intermediateRDDStorageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK,
             finalRDDStorageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK,
             checkpointPath: Option[String] = None,
             checkpointInterval: Int = 0,
             verbose: Boolean = false)
  extends Serializable with Logging {

  protected def gamma: Float = 1f

  protected def cacheAndCount[E](rdd: RDD[E]): RDD[E] = {
    val r = rdd.persist(intermediateRDDStorageLevel)
    r.count()
    r
  }

  private def checkpoint(emb: RDD[ItemData],
                         path: String,
                        )(implicit sc: SparkContext): RDD[ItemData] = {
    val sqlc = new SQLContext(sc)
    import sqlc.implicits._
    if (emb != null) {
      emb.map(itemData => (itemData.t, itemData.id, itemData.cn, itemData.f))
        .toDF("t", "id", "cn", "f")
        .write.mode(SaveMode.Overwrite).parquet(path)
      emb.unpersist()
    }

    cacheAndCount(sqlc.read.parquet(path)
      .as[(Boolean, Long, Long, Array[Float])].rdd
      .map(e => new ItemData(e._1, e._2, e._3, e._4)))
  }

  private def listFiles(path: String): Array[String] = {
    val hdfs = FileSystem.get(new Configuration())
    Try(hdfs.listStatus(new Path(path)).map(_.getPath.getName)).getOrElse(Array.empty)
  }

  protected def pairs(data: RDD[T],
                      partitioner1: Partitioner,
                      partitioner2: Partitioner,
                      seed: Long): RDD[LongPairMulti]

  protected def initialize(data: RDD[T]): RDD[ItemData]

  private[recommendation] def train(data: RDD[T]): RDD[ItemData] = {
    val sparkContext = data.sparkContext

    val latest = if (checkpointPath.isDefined) {
      listFiles(checkpointPath.get)
        .filter(file => listFiles(checkpointPath.get + "/" + file).contains("_SUCCESS"))
        .map(_.split("_").map(_.toInt)).map{case Array(a, b) => (a, b)}
        .sorted.lastOption
    } else {
      None
    }

    latest.foreach(x => log.info(s"Continue training from epoch = ${x._1}, iteration = ${x._2}"))
    val cached = ArrayBuffer.empty[RDD[ItemData]]

    var emb = latest
      .map(x => checkpoint(null, checkpointPath.get + "/" + x._1 + "_" + x._2)(sparkContext))
      .getOrElse{cacheAndCount(initialize(data))}
    cached += emb

    var checkpointIter = 0
    val (startEpoch, startIter) = latest.getOrElse((0, 0))
    val partitionTable = sparkContext.broadcast(LogisticFactorizationBase
      .createPartitionTable(numPartitions, new Random(seed)))

    (startEpoch until numIterations).foreach {curEpoch =>

      val partitioner1 = new HashPartitioner(numPartitions) {
        override def getPartition(item: Any): Int = {
          LogisticFactorizationBase.hash(item.asInstanceOf[Long], curEpoch, numPartitions)
        }
      }

      ((if (curEpoch == startEpoch) startIter else 0) until numPartitions).foreach { pI =>
        val progress = (1.0 * curEpoch.toDouble * numPartitions + pI) /
          (numIterations * numPartitions)

        val curLearningRate = minLearningRate.fold(learningRate)(e =>
          Math.exp(Math.log(learningRate) -
            (Math.log(learningRate) - Math.log(e)) * progress))

        val partitioner2 = new HashPartitioner(numPartitions) {
          override def getPartition(item: Any): Int = {
            val bucket = LogisticFactorizationBase.hash(
              item.asInstanceOf[Long],
              curEpoch,
              partitionTable.value.length)
            partitionTable.value.apply(bucket).apply(pI)
          }
        }

        val partitionerKey = new HashPartitioner(numPartitions) {
          override def getPartition(key: Any): Int = key.asInstanceOf[Int]
        }

        val embLR = emb
          .keyBy(i => if (i.t == ItemData.TYPE_LEFT) {
            partitioner1.getPartition(i.id)
          } else {
            partitioner2.getPartition(i.id)
          }).partitionBy(partitionerKey).values

        val cur = pairs(data, partitioner1, partitioner2,
            (1L * curEpoch * numPartitions + pI) * numPartitions)
          .map(e => e.part -> e).partitionBy(partitionerKey).values

        emb = cur.zipPartitions(embLR) { case (sIt, eItLR) =>
          val sg = Optimizer(new Opts(dotVectorSize, useBias,
            negative, pow, curLearningRate, lambda, gamma, implicitPrefs, verbose), eItLR)

          sg.optimize(sIt, numThread)
          if (verbose) {
            log.debug(
              "LOSS: " + sg.loss.doubleValue() / sg.lossn.longValue() +
                " (" + sg.loss.doubleValue() + " / " + sg.lossn.longValue() + ")" + "\t" +
                sg.lossReg.doubleValue() / sg.lossnReg.longValue() +
                " (" + sg.lossReg.doubleValue() + " / " + sg.lossnReg.longValue() + ")")
          }

          sg.flush()
        }.persist(intermediateRDDStorageLevel)

        cached += emb

        if (checkpointInterval > 0 && (checkpointIter + 1) % checkpointInterval == 0) {
          emb = checkpoint(emb, checkpointPath.get + "/" + curEpoch + "_" + (pI + 1))(sparkContext)

          cached.foreach(_.unpersist())
          cached.clear()

          cached += emb
        }
        checkpointIter += 1
      }
    }

    emb.persist(finalRDDStorageLevel)
    emb.count()

    cached.foreach(_.unpersist())
    cached.clear()

    emb
  }
}