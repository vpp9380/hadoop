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
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.EnumSet;

import junit.framework.Assert;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSOutputStream;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream.SyncFlag;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager.Lease;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestFSImage {

  private MiniDFSCluster cluster;
  private Configuration conf;
  private DistributedFileSystem fs;
  private FSNamesystem fsn;

  @Before
  public void setUp() throws IOException {
    conf = new Configuration();
    cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    fsn = cluster.getNamesystem();
    fs = cluster.getFileSystem();
  }

  @After
  public void tearDown() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testINode() throws IOException {
    final Path dir = new Path("/abc/def");
    final Path file1 = new Path(dir, "f1");
    final Path file2 = new Path(dir, "f2");

    // create an empty file f1
    fs.create(file1).close();

    // create an under-construction file f2
    FSDataOutputStream out = fs.create(file2);
    out.writeBytes("hello");
    ((DFSOutputStream) out.getWrappedStream()).hsync(EnumSet
        .of(SyncFlag.UPDATE_LENGTH));

    // checkpoint
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    cluster.restartNameNode();
    cluster.waitActive();
    fs = cluster.getFileSystem();

    assertTrue(fs.isDirectory(dir));
    assertTrue(fs.exists(file1));
    assertTrue(fs.exists(file2));

    // check internals of file2
    INodeFile file2Node = fsn.dir.getINode4Write(file2.toString()).asFile();
    assertEquals("hello".length(), file2Node.computeFileSize());
    assertTrue(file2Node.isUnderConstruction());
    BlockInfo[] blks = file2Node.getBlocks();
    assertEquals(1, blks.length);
    assertEquals(BlockUCState.UNDER_CONSTRUCTION, blks[0].getBlockUCState());
    // check lease manager
    Lease lease = fsn.leaseManager.getLeaseByPath(file2.toString());
    Assert.assertNotNull(lease);
  }
}
