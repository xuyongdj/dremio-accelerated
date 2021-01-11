/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.op.filter;

import java.util.ArrayList;
import java.util.stream.Stream;

import javax.inject.Named;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.ValueVector;

import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.selection.SelectionVector2;
import com.dremio.exec.record.selection.SelectionVector4;
import com.dremio.sabot.exec.context.FunctionContext;

/* TODO: Remove shared code with FilterTemplate2 */
public abstract class FilterTemplateAccelerated implements Filterer {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FilterTemplate2.class);

  private SelectionVector4 outgoingSelectionVector;
  private SelectionVector2 incomingSelectionVector;
  private SelectionVectorMode svMode;

  private VectorAccessible incomingVec;

  // Load native library libNativeFilter.so
  static {
    System.load("/usr/lib64/libNativeFilter.so");
  }

  private native int doNativeEval(int recordCount, long[] inAddresses, long[] inSizes, long outAddress, long outSize);

  @Override
  public void setup(FunctionContext context, VectorAccessible incoming, VectorAccessible outgoing) throws SchemaChangeException {
    // this.outgoingSelectionVector = outgoing.getSelectionVector2();
    this.outgoingSelectionVector = outgoing.getSelectionVector4();
    this.svMode = incoming.getSchema().getSelectionVectorMode();
    this.incomingVec = incoming;

    switch(svMode){
      case NONE:
        break;
      case TWO_BYTE:
        this.incomingSelectionVector = incoming.getSelectionVector2();
        break;
      default:
        // SV4 is handled in FilterTemplate4
        throw new UnsupportedOperationException();
    }
    doSetup(context, incoming, outgoing);
  }

  public int filterBatch(int recordCount){
    if (recordCount == 0) {
      return 0;
    }

    outgoingSelectionVector.allocateNew(recordCount);

    final int outputRecords;
    switch(svMode){
      case NONE:
        outputRecords = filterBatchNoSV(recordCount);
        break;
      case TWO_BYTE:
        outputRecords = filterBatchSV2(recordCount);
        break;
      default:
        throw new UnsupportedOperationException();
    }
    return outputRecords;
  }

  private int filterBatchSV2(int recordCount){
    int svIndex = 0;
    final int count = recordCount;
    for(int i = 0; i < count; i++){
      char index = incomingSelectionVector.getIndex(i);
      if(true){
        outgoingSelectionVector.setIndex(svIndex, index);
        svIndex++;
      }
    }
    outgoingSelectionVector.setRecordCount(svIndex);
    return svIndex;
  }

  // NATIVE FILTER IMPLEMENTATION
  //
  // Filter matching is offloaded to native code, which computes the SV4 selection vector
  // Original java implementation:
  //
  //    int svIndex = 0;
  //    for(int i = 0; i < recordCount; i++){
  //      if(doEval(i, 0){
  //        outgoingSelectionVector.setIndex(svIndex, i);
  //        svIndex++;
  //      }
  //    }
  //    outgoingSelectionVector.setRecordCount(svIndex);
  //    return svIndex;
  //
  private int filterBatchNoSV(int recordCount){

    // Cast incoming VectorAccessible to VectorContainer for easier access to the arrow buffers
    VectorContainer in = (VectorContainer) this.incomingVec;

    // Extract input buffers and compute their addresses and sizes
    ArrowBuf[] inBuffers = in.getValueAccessorById(ValueVector.class, 1).getValueVector().getBuffers(false);
    long[] inAddresses = Stream.of(inBuffers).mapToLong(b -> b.memoryAddress()).toArray();
    long[] inSizes = Stream.of(inBuffers).mapToLong(b -> b.readableBytes()).toArray();

    // TODO: recordcount en length
    // Extract output buffer and compute its address and size
    ArrowBuf outBuffer = outgoingSelectionVector.getBuffer(false);
    long outAddress = outBuffer.memoryAddress();
    long outSize = outBuffer.writableBytes();

    // Call native function, which computes the selection vector and returns the number of matched records
    int matchedRecords = doNativeEval(recordCount, inAddresses, inSizes, outAddress, outSize);

    // TODO: Remove this
    // Print the bytes in the outBuffer for debug
    ArrayList<Byte> byteArr = new ArrayList<Byte>();
    for (int i = 0; i < matchedRecords; i++) {
      byteArr.add(outgoingSelectionVector.getBuffer(false).getByte(i));
    }
    System.out.println(byteArr);

    outgoingSelectionVector.setRecordCount(matchedRecords);
    return matchedRecords;

  }

  public abstract void doSetup(@Named("context") FunctionContext context, @Named("incoming") VectorAccessible incoming, @Named("outgoing") VectorAccessible outgoing);
  public abstract boolean doEval(@Named("inIndex") int inIndex, @Named("outIndex") int outIndex);

}
