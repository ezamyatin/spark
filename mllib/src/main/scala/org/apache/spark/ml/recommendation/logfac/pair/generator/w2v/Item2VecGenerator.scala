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
package org.apache.spark.ml.recommendation.logfac.pair.generator.w2v

import java.util.Random

import org.apache.spark.Partitioner
import org.apache.spark.ml.recommendation.logfac.local.PrimitiveArrayBuffer
import org.apache.spark.ml.recommendation.logfac.pair.LongPair


private[ml] class Item2VecGenerator(sent: Iterator[Array[Long]],
                                    private val window: Int,
                                    partitioner1: Partitioner,
                                    partitioner2: Partitioner,
                                    seed: Long
                                   ) extends PairGenerator(sent, partitioner1, partitioner2) {
  final private val p1 = PrimitiveArrayBuffer.empty[Int]
  final private val p2 = PrimitiveArrayBuffer.empty[Int]
  final private val random = new Random(seed)

  override protected def generate(sent: Array[Long]): Iterator[LongPair] = {
    p1.clear()
    p2.clear()

    sent.indices.foreach{i =>
      p1.add(partitioner1.getPartition(sent(i)))
      p2.add(partitioner2.getPartition(sent(i)))
    }

    new Iterator[LongPair] {
      private var i = 0
      private var j = 0

      override def hasNext: Boolean = true

      override def next(): LongPair = {
        while (i < sent.length) {
          val n = Math.min(2 * window, sent.length - 1)
          while (j < n) {
            var c = i
            while (c == i) {
              c = random.nextInt(sent.length)
            }

            j += 1

            if ((p1.get(i) == p2.get(c)) && sent(i) != sent(c)) {
              return LongPair(p1.get(i), sent(i), sent(c))
            }
          }
          i += 1
          j = 0
        }
        null
      }
    }.takeWhile(_ != null)
  }
}