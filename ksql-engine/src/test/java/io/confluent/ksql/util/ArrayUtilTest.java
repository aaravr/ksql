/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.util;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArrayUtilTest {

  @Test
  public void shouldGetCorrectNullIndex() {
    final Double[] doubles1 = new Double[]{10.0, null, null};
    final Double[] doubles2 = new Double[]{null, null, null};
    final Double[] doubles3 = new Double[]{10.0, 9.0, 8.0};

    assertThat(ArrayUtil.getNullIndex(doubles1), equalTo(1));
    assertThat(ArrayUtil.getNullIndex(doubles2), equalTo(0));
    assertThat(ArrayUtil.getNullIndex(doubles3), equalTo(-1));
  }

  @Test
  public void shouldCheckArrayItemsCorrectly() {
    final Double[] doubles = new Double[]{10.0, null, null};
    final Long[] longs = new Long[]{10L, 35L, 70L, null};
    final Integer[] integers = new Integer[]{10, 35, 70, null};
    final String[] strings = new String[]{"Hello", "hi", "bye", null};

    assertTrue(ArrayUtil.containsValue(10.0, doubles));
    assertTrue(ArrayUtil.containsValue(35L, longs));
    assertTrue(ArrayUtil.containsValue(70, integers));
    assertTrue(ArrayUtil.containsValue("hi", strings));
  }
}
