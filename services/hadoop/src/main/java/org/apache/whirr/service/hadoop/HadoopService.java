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

package org.apache.whirr.service.hadoop;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.whirr.service.RunUrlBuilder.runUrls;
import static org.jclouds.compute.options.TemplateOptions.Builder.runScript;
import static org.jclouds.io.Payloads.newStringPayload;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.whirr.service.Cluster.Instance;
import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.ClusterSpec.InstanceTemplate;
import org.apache.whirr.service.ComputeServiceContextBuilder;
import org.apache.whirr.service.Service;
import org.apache.whirr.service.jclouds.FirewallSettings;
import org.apache.whirr.service.jclouds.TemplateBuilderStrategy;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.io.Payload;

public class HadoopService extends Service {
  
  public static final Set<String> MASTER_ROLE = Sets.newHashSet("nn", "jt");
  public static final Set<String> WORKER_ROLE = Sets.newHashSet("dn", "tt");
  
  public static final int WEB_PORT = 80;
  public static final int NAMENODE_PORT = 8020;
  public static final int JOBTRACKER_PORT = 8021;
  public static final int NAMENODE_WEB_UI_PORT = 50070;
  public static final int JOBTRACKER_WEB_UI_PORT = 50030;

  @Override
  public String getName() {
    return "hadoop";
  }
  
  @Override
  public HadoopCluster launchCluster(ClusterSpec clusterSpec) throws IOException {
    ComputeServiceContext computeServiceContext =
      ComputeServiceContextBuilder.build(clusterSpec);
    ComputeService computeService = computeServiceContext.getComputeService();
    
    // Launch Hadoop "master" (NN and JT)
    // deal with user packages and autoshutdown with extra runurls
    String hadoopInstallRunUrl = clusterSpec.getConfiguration().getString(
        "whirr.hadoop-install-runurl", "apache/hadoop/install");
    Payload nnjtBootScript = newStringPayload(runUrls(clusterSpec.getRunUrlBase(),
      "sun/java/install",
      String.format("%s nn,jt -c %s", hadoopInstallRunUrl,
          clusterSpec.getProvider())));

    TemplateBuilder masterTemplateBuilder = computeService.templateBuilder()
      .options(runScript(nnjtBootScript)
      .installPrivateKey(clusterSpec.getPrivateKey())
      .authorizePublicKey(clusterSpec.getPublicKey()));
    
    TemplateBuilderStrategy strategy = new HadoopTemplateBuilderStrategy();
    strategy.configureTemplateBuilder(clusterSpec, masterTemplateBuilder);
    
    Template masterTemplate = masterTemplateBuilder.build();
    
    InstanceTemplate instanceTemplate = clusterSpec.getInstanceTemplate(MASTER_ROLE);
    checkNotNull(instanceTemplate);
    checkArgument(instanceTemplate.getNumberOfInstances() == 1);
    Set<? extends NodeMetadata> nodes;
    try {
      nodes = computeService.runNodesWithTag(
          clusterSpec.getClusterName(), 1, masterTemplate);
    } catch (RunNodesException e) {
      // TODO: can we do better here (retry?)
      throw new IOException(e);
    }
    NodeMetadata node = Iterables.getOnlyElement(nodes);
    InetAddress namenodePublicAddress = InetAddress.getByName(Iterables.get(node.getPublicAddresses(),0));
    InetAddress jobtrackerPublicAddress = InetAddress.getByName(Iterables.get(node.getPublicAddresses(),0));
    
    FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
        WEB_PORT);
    FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
        NAMENODE_WEB_UI_PORT);
    FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
        JOBTRACKER_WEB_UI_PORT);
    FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
        namenodePublicAddress.getHostAddress(), NAMENODE_PORT);
    FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
        namenodePublicAddress.getHostAddress(), JOBTRACKER_PORT);
    if (!namenodePublicAddress.equals(jobtrackerPublicAddress)) {
      FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
          jobtrackerPublicAddress.getHostAddress(), NAMENODE_PORT);
      FirewallSettings.authorizeIngress(computeServiceContext, node, clusterSpec,
          jobtrackerPublicAddress.getHostAddress(), JOBTRACKER_PORT);
    }

    // Launch slaves (DN and TT)
    Payload slaveBootScript = newStringPayload(runUrls(clusterSpec.getRunUrlBase(),
      "sun/java/install",
      String.format("%s dn,tt -n %s -j %s -c %s",
          hadoopInstallRunUrl,
          namenodePublicAddress.getHostName(),
          jobtrackerPublicAddress.getHostName(),
          clusterSpec.getProvider())));

    TemplateBuilder slaveTemplateBuilder = computeService.templateBuilder()
      .options(runScript(slaveBootScript)
      .installPrivateKey(clusterSpec.getPrivateKey())
      .authorizePublicKey(clusterSpec.getPublicKey()));

    strategy.configureTemplateBuilder(clusterSpec, slaveTemplateBuilder);
    
    Template slaveTemplate = slaveTemplateBuilder.build();
    
    instanceTemplate = clusterSpec.getInstanceTemplate(WORKER_ROLE);
    checkNotNull(instanceTemplate);

    Set<? extends NodeMetadata> workerNodes;
    try {
      workerNodes = computeService.runNodesWithTag(clusterSpec.getClusterName(),
        instanceTemplate.getNumberOfInstances(), slaveTemplate);
    } catch (RunNodesException e) {
      // TODO: don't bail out if only a few have failed to start
      throw new IOException(e);
    }
    
    // TODO: wait for TTs to come up (done in test for the moment)
    
    Set<Instance> instances = Sets.union(getInstances(MASTER_ROLE, Collections.singleton(node)),
      getInstances(WORKER_ROLE, workerNodes));
    
    Properties config = createClientSideProperties(namenodePublicAddress, jobtrackerPublicAddress);
    return new HadoopCluster(instances, config);
  }
  
  private Set<Instance> getInstances(final Set<String> roles, Set<? extends NodeMetadata> nodes) {
    return Sets.newHashSet(Collections2.transform(Sets.newHashSet(nodes),
        new Function<NodeMetadata, Instance>() {
      @Override
      public Instance apply(NodeMetadata node) {
        try {
        return new Instance(node.getCredentials(), roles,
            InetAddress.getByName(Iterables.get(node.getPublicAddresses(), 0)),
            InetAddress.getByName(Iterables.get(node.getPrivateAddresses(), 0)));
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
      }
    }));
  }
  
  private Properties createClientSideProperties(InetAddress namenode, InetAddress jobtracker) throws IOException {
      Properties config = new Properties();
      config.setProperty("hadoop.job.ugi", "root,root");
      config.setProperty("fs.default.name", String.format("hdfs://%s:8020/", namenode.getHostName()));
      config.setProperty("mapred.job.tracker", String.format("%s:8021", jobtracker.getHostName()));
      config.setProperty("hadoop.socks.server", "localhost:6666");
      config.setProperty("hadoop.rpc.socket.factory.class.default", "org.apache.hadoop.net.SocksSocketFactory");
      return config;
  }

  private void createClientSideHadoopSiteFile(InetAddress namenode, InetAddress jobtracker) throws IOException {
    File file = new File("/tmp/hadoop-site.xml");
    Files.write(generateHadoopConfigurationFile(createClientSideProperties(namenode, jobtracker)), file, Charsets.UTF_8);
  }
  
  private CharSequence generateHadoopConfigurationFile(Properties config) {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\"?>\n");
    sb.append("<?xml-stylesheet type=\"text/xsl\" href=\"configuration.xsl\"?>\n");
    sb.append("<configuration>\n");
    for (Entry<Object, Object> entry : config.entrySet()) {
      sb.append("<property>\n");
      sb.append("<name>").append(entry.getKey()).append("</name>\n");
      sb.append("<value>").append(entry.getValue()).append("</value>\n");
      sb.append("</property>\n");
    }
    sb.append("</configuration>\n");
    return sb;
  }
  
}
