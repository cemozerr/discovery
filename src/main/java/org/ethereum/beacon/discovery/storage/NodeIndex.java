/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.storage;

import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

/** Node Index. Stores several node keys. */
public class NodeIndex {
  private List<Bytes> entries;

  public NodeIndex() {
    this.entries = new ArrayList<>();
  }

  public static NodeIndex fromRlpBytes(Bytes bytes) {
    RlpList internalList = (RlpList) RlpDecoder.decode(bytes.toArray()).getValues().get(0);
    List<Bytes> entries = new ArrayList<>();
    for (RlpType entry : internalList.getValues()) {
      entries.add(Bytes.wrap(((RlpString) entry).getBytes()));
    }
    NodeIndex res = new NodeIndex();
    res.setEntries(entries);
    return res;
  }

  public List<Bytes> getEntries() {
    return entries;
  }

  public void setEntries(List<Bytes> entries) {
    this.entries = entries;
  }

  public Bytes toRlpBytes() {
    List<RlpType> values = new ArrayList<>();
    for (Bytes entryBytes : getEntries()) {
      values.add(RlpString.create(entryBytes.toArray()));
    }
    byte[] bytes = RlpEncoder.encode(new RlpList(values));
    return Bytes.wrap(bytes);
  }
}
