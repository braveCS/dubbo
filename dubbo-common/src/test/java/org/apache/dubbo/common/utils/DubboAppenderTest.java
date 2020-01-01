/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.common.utils;


import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class DubboAppenderTest {
    private LogEvent event;

    @Before
    public void setUp() throws Exception {
        event = Mockito.mock(LogEvent.class);
        Mockito.when(event.getLevel()).thenReturn(Level.DEBUG);
        Mockito.when(event.getThreadName()).thenReturn("thread-name");
        Mockito.when(event.getMessage()).thenReturn(new SimpleMessage("asdfasdfsadf"));
    }

    @After
    public void tearDown() throws Exception {
        DubboAppender.clear();
        DubboAppender.doStop();
    }

    @Test
    public void testAvailable() throws Exception {
        assertThat(DubboAppender.available, is(false));
        DubboAppender.doStart();
        assertThat(DubboAppender.available, is(true));
        DubboAppender.doStop();
        assertThat(DubboAppender.available, is(false));
    }

    @Test
    public void testAppend() throws Exception {
        DubboAppender appender = new DubboAppender("asdf",null,null,false);
        appender.append(event);
        assertThat(DubboAppender.logList, hasSize(0));
        DubboAppender.doStart();
        appender.append(event);
        assertThat(DubboAppender.logList, hasSize(1));
        Log log = DubboAppender.logList.get(0);
        assertThat(log.getLogThread(), equalTo("thread-name"));
    }

    //@Test
    public void testClear() throws Exception {
        DubboAppender.doStart();
        DubboAppender appender = new DubboAppender("asdf",null,null,false);
        appender.append(event);
        assertThat(DubboAppender.logList, hasSize(1));
        DubboAppender.clear();
        assertThat(DubboAppender.logList, hasSize(0));
    }
}

