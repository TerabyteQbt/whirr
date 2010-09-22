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

package org.apache.whirr.service.zookeeper;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.whirr.service.RunUrlBuilder.runUrls;
import static org.jclouds.compute.domain.OsFamily.UBUNTU;
import static org.jclouds.compute.options.TemplateOptions.Builder.runScript;
import static org.jclouds.compute.predicates.NodePredicates.runningWithTag;
import static org.jclouds.io.Payloads.newStringPayload;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.ComputeServiceContextBuilder;
import org.apache.whirr.service.Service;
import org.apache.whirr.service.Cluster.Instance;
import org.apache.whirr.service.ClusterSpec.InstanceTemplate;
import org.apache.whirr.service.jclouds.FirewallSettings;
import org.jclouds.aws.ec2.domain.InstanceType;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.io.Payload;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ZooKeeperService extends Service {
    
  public static final String ZOOKEEPER_ROLE = "zk";
  private static final int CLIENT_PORT = 2181;
  
  @Override
  public String getName() {
    return "zookeeper";
  }
  
  @Override
  public ZooKeeperCluster launchCluster(ClusterSpec clusterSpec) throws IOException {
    ComputeServiceContext computeServiceContext =
      ComputeServiceContextBuilder.build(clusterSpec);
    ComputeService computeService = computeServiceContext.getComputeService();

    Payload bootScript = newStringPayload(runUrls(clusterSpec.getRunUrlBase(),
      "sun/java/install",
      "apache/zookeeper/install"));
    
    TemplateBuilder templateBuilder = computeService.templateBuilder()
      .osFamily(UBUNTU)
      .options(runScript(bootScript)
      .installPrivateKey(clusterSpec.getPrivateKey())
      .authorizePublicKey(clusterSpec.getPublicKey()));
    
    // TODO extract this logic elsewhere
    if (clusterSpec.getProvider().equals("ec2"))
       templateBuilder.osVersionMatches("10.04")
      .imageDescriptionMatches(".*ubuntu-images.*")
      .hardwareId(InstanceType.M1_SMALL);
    
    Template template = templateBuilder.build();
    
    InstanceTemplate instanceTemplate = clusterSpec.getInstanceTemplate(ZOOKEEPER_ROLE);
    checkNotNull(instanceTemplate);
    int ensembleSize = instanceTemplate.getNumberOfInstances();
    Set<? extends NodeMetadata> nodeMap;
    try {
      nodeMap = computeService.runNodesWithTag(clusterSpec.getClusterName(), ensembleSize,
      template);
    } catch (RunNodesException e) {
      // TODO: can we do better here - proceed if ensemble is big enough?
      throw new IOException(e);
    }
    
    FirewallSettings.authorizeIngress(computeServiceContext, nodeMap, clusterSpec, CLIENT_PORT);
    
    List<NodeMetadata> nodes = Lists.newArrayList(nodeMap);
    
    // Pass list of all servers in ensemble to configure script.
    // Position is significant: i-th server has id i.
    String servers = Joiner.on(' ').join(getPrivateIps(nodes));
    Payload configureScript = newStringPayload(runUrls(clusterSpec.getRunUrlBase(),
      "apache/zookeeper/post-configure " + servers));
    try {
      computeService.runScriptOnNodesMatching(runningWithTag(clusterSpec.getClusterName()), configureScript);
    } catch (RunScriptOnNodesException e) {
      // TODO: retry
      throw new IOException(e);
    }
    
    String hosts = Joiner.on(',').join(getHosts(nodes));
    return new ZooKeeperCluster(getInstances(nodes), hosts);
  }

  private List<String> getPrivateIps(List<NodeMetadata> nodes) {
    return Lists.transform(Lists.newArrayList(nodes),
        new Function<NodeMetadata, String>() {
      @Override
      public String apply(NodeMetadata node) {
        try {
         return InetAddress.getByName(Iterables.get(node.getPrivateAddresses(), 0)).getHostAddress();
      } catch (UnknownHostException e) {
         Throwables.propagate(e);
         return null;
      }
      }
    });
  }
  
  private Set<Instance> getInstances(List<NodeMetadata> nodes) {
    return Sets.newHashSet(Collections2.transform(Sets.newHashSet(nodes),
        new Function<NodeMetadata, Instance>() {
      @Override
      public Instance apply(NodeMetadata node) {
        try {
        return new Instance(node.getCredentials(), Collections.singleton(ZOOKEEPER_ROLE),
          InetAddress.getByName(Iterables.get(node.getPublicAddresses(), 0)),
          InetAddress.getByName(Iterables.get(node.getPrivateAddresses(), 0)));
        } catch (UnknownHostException e) {
          throw new RuntimeException(e);
        }
      }
    }));
  }
  
  private List<String> getHosts(List<NodeMetadata> nodes) {
    return Lists.transform(Lists.newArrayList(nodes),
        new Function<NodeMetadata, String>() {
      @Override
      public String apply(NodeMetadata node) {
        String publicIp;
      try {
         publicIp = InetAddress.getByName(Iterables.get(node.getPublicAddresses(), 0)).getHostName();
      } catch (UnknownHostException e) {
         Throwables.propagate(e);
         return null;
      }
        return String.format("%s:%d", publicIp, CLIENT_PORT);
      }
    });
  }

}
