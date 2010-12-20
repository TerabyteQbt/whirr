/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.whirr.service;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

import java.io.IOException;

import org.junit.Test;

public class ServiceFactoryTest {
  
  public static class TestService extends Service {
    @Override
    public String getName() {
      return "test-service";
    }
    @Override
    public Cluster launchCluster(ClusterSpec clusterSpec) throws IOException {
      return null;
    }
  }
  
  @Test
  public void testServiceFactoryIsCreatedFromWhirrProperties() throws IOException {
    ServiceFactory factory = new ServiceFactory();
    Service service = factory.create("test-service");
    assertThat(service, instanceOf(TestService.class));
  }

  @Test
  public void testServiceFactoryWithNullServiceName() throws IOException {
    ServiceFactory factory = new ServiceFactory();
    Service service = factory.create(null);
    assertThat(service, instanceOf(Service.class));
  }
}
