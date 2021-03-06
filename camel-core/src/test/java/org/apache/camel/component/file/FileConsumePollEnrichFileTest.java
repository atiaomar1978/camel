/**
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
package org.apache.camel.component.file;

import org.apache.camel.ContextTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;

public class FileConsumePollEnrichFileTest extends ContextTestSupport {

    @Override
    protected void setUp() throws Exception {
        deleteDirectory("target/enrich");
        deleteDirectory("target/enrichdata");
        super.setUp();
    }

    public void testPollEnrich() throws Exception {
        getMockEndpoint("mock:start").expectedBodiesReceived("Start");

        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived("Big file");

        template.sendBodyAndHeader("file://target/enrich", "Start", Exchange.FILE_NAME, "AAA.fin");

        log.info("Sleeping for 2 sec before writing enrichdata file");
        Thread.sleep(2000);
        template.sendBodyAndHeader("file://target/enrichdata", "Big file", Exchange.FILE_NAME, "AAA.dat");
        log.info("... write done");

        mock.assertIsSatisfied();

        // because the on completion is executed async, we should wait a bit to not fail on slow CI servers
        Thread.sleep(200);
        assertFileExists("target/enrich/.done/AAA.fin");
        assertFileExists("target/enrichdata/.done/AAA.dat");
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("file://target/enrich?move=.done")
                    .to("mock:start")
                    .pollEnrich("file://target/enrichdata?move=.done", 20000)
                    .to("mock:result");
            }
        };
    }

}