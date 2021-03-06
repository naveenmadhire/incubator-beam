/*
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
package org.apache.beam.runners.flink.translation;

import org.apache.beam.runners.flink.io.ConsoleIO;
import org.apache.beam.runners.flink.translation.functions.FlinkCoGroupKeyedListAggregator;
import org.apache.beam.runners.flink.translation.functions.FlinkCreateFunction;
import org.apache.beam.runners.flink.translation.functions.FlinkDoFnFunction;
import org.apache.beam.runners.flink.translation.functions.FlinkKeyedListAggregationFunction;
import org.apache.beam.runners.flink.translation.functions.FlinkMultiOutputDoFnFunction;
import org.apache.beam.runners.flink.translation.functions.FlinkMultiOutputPruningFunction;
import org.apache.beam.runners.flink.translation.functions.FlinkPartialReduceFunction;
import org.apache.beam.runners.flink.translation.functions.FlinkReduceFunction;
import org.apache.beam.runners.flink.translation.functions.UnionCoder;
import org.apache.beam.runners.flink.translation.types.CoderTypeInformation;
import org.apache.beam.runners.flink.translation.types.KvCoderTypeInformation;
import org.apache.beam.runners.flink.translation.wrappers.SinkOutputFormat;
import org.apache.beam.runners.flink.translation.wrappers.SourceInputFormat;
import com.google.api.client.util.Maps;
import com.google.cloud.dataflow.sdk.coders.CannotProvideCoderException;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.io.AvroIO;
import com.google.cloud.dataflow.sdk.io.BoundedSource;
import com.google.cloud.dataflow.sdk.io.Read;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.transforms.Write;
import com.google.cloud.dataflow.sdk.transforms.join.CoGbkResult;
import com.google.cloud.dataflow.sdk.transforms.join.CoGbkResultSchema;
import com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey;
import com.google.cloud.dataflow.sdk.transforms.join.KeyedPCollectionTuple;
import com.google.cloud.dataflow.sdk.transforms.join.RawUnionValue;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.collect.Lists;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.operators.Keys;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.io.AvroInputFormat;
import org.apache.flink.api.java.io.AvroOutputFormat;
import org.apache.flink.api.java.io.TextInputFormat;
import org.apache.flink.api.java.operators.CoGroupOperator;
import org.apache.flink.api.java.operators.DataSink;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.api.java.operators.FlatMapOperator;
import org.apache.flink.api.java.operators.GroupCombineOperator;
import org.apache.flink.api.java.operators.GroupReduceOperator;
import org.apache.flink.api.java.operators.Grouping;
import org.apache.flink.api.java.operators.MapPartitionOperator;
import org.apache.flink.api.java.operators.UnsortedGrouping;
import org.apache.flink.core.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translators for transforming
 * Dataflow {@link com.google.cloud.dataflow.sdk.transforms.PTransform}s to
 * Flink {@link org.apache.flink.api.java.DataSet}s
 */
public class FlinkBatchTransformTranslators {

  // --------------------------------------------------------------------------------------------
  //  Transform Translator Registry
  // --------------------------------------------------------------------------------------------

  @SuppressWarnings("rawtypes")
  private static final Map<Class<? extends PTransform>, FlinkBatchPipelineTranslator.BatchTransformTranslator> TRANSLATORS = new HashMap<>();

  // register the known translators
  static {
    TRANSLATORS.put(View.CreatePCollectionView.class, new CreatePCollectionViewTranslatorBatch());

    TRANSLATORS.put(Combine.PerKey.class, new CombinePerKeyTranslatorBatch());
    // we don't need this because we translate the Combine.PerKey directly
    //TRANSLATORS.put(Combine.GroupedValues.class, new CombineGroupedValuesTranslator());

    TRANSLATORS.put(Create.Values.class, new CreateTranslatorBatch());

    TRANSLATORS.put(Flatten.FlattenPCollectionList.class, new FlattenPCollectionTranslatorBatch());

    // TODO we're currently ignoring windows here but that has to change in the future
    TRANSLATORS.put(GroupByKey.class, new GroupByKeyTranslatorBatch());

    TRANSLATORS.put(ParDo.BoundMulti.class, new ParDoBoundMultiTranslatorBatch());
    TRANSLATORS.put(ParDo.Bound.class, new ParDoBoundTranslatorBatch());

    TRANSLATORS.put(CoGroupByKey.class, new CoGroupByKeyTranslatorBatch());

    TRANSLATORS.put(AvroIO.Read.Bound.class, new AvroIOReadTranslatorBatch());
    TRANSLATORS.put(AvroIO.Write.Bound.class, new AvroIOWriteTranslatorBatch());

    TRANSLATORS.put(Read.Bounded.class, new ReadSourceTranslatorBatch());
    TRANSLATORS.put(Write.Bound.class, new WriteSinkTranslatorBatch());

    TRANSLATORS.put(TextIO.Read.Bound.class, new TextIOReadTranslatorBatch());
    TRANSLATORS.put(TextIO.Write.Bound.class, new TextIOWriteTranslatorBatch());

    // Flink-specific
    TRANSLATORS.put(ConsoleIO.Write.Bound.class, new ConsoleIOWriteTranslatorBatch());

  }


  public static FlinkBatchPipelineTranslator.BatchTransformTranslator<?> getTranslator(PTransform<?, ?> transform) {
    return TRANSLATORS.get(transform.getClass());
  }

  private static class ReadSourceTranslatorBatch<T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<Read.Bounded<T>> {

    @Override
    public void translateNode(Read.Bounded<T> transform, FlinkBatchTranslationContext context) {
      String name = transform.getName();
      BoundedSource<T> source = transform.getSource();
      PCollection<T> output = context.getOutput(transform);
      Coder<T> coder = output.getCoder();

      TypeInformation<T> typeInformation = context.getTypeInfo(output);

      DataSource<T> dataSource = new DataSource<>(context.getExecutionEnvironment(),
          new SourceInputFormat<>(source, context.getPipelineOptions()), typeInformation, name);

      context.setOutputDataSet(output, dataSource);
    }
  }

  private static class AvroIOReadTranslatorBatch<T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<AvroIO.Read.Bound<T>> {
    private static final Logger LOG = LoggerFactory.getLogger(AvroIOReadTranslatorBatch.class);

    @Override
    public void translateNode(AvroIO.Read.Bound<T> transform, FlinkBatchTranslationContext context) {
      String path = transform.getFilepattern();
      String name = transform.getName();
//      Schema schema = transform.getSchema();
      PValue output = context.getOutput(transform);

      TypeInformation<T> typeInformation = context.getTypeInfo(output);

      // This is super hacky, but unfortunately we cannot get the type otherwise
      Class<T> extractedAvroType;
      try {
        Field typeField = transform.getClass().getDeclaredField("type");
        typeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Class<T> avroType = (Class<T>) typeField.get(transform);
        extractedAvroType = avroType;
      } catch (NoSuchFieldException | IllegalAccessException e) {
        // we know that the field is there and it is accessible
        throw new RuntimeException("Could not access type from AvroIO.Bound", e);
      }

      DataSource<T> source = new DataSource<>(context.getExecutionEnvironment(),
          new AvroInputFormat<>(new Path(path), extractedAvroType),
          typeInformation, name);

      context.setOutputDataSet(output, source);
    }
  }

  private static class AvroIOWriteTranslatorBatch<T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<AvroIO.Write.Bound<T>> {
    private static final Logger LOG = LoggerFactory.getLogger(AvroIOWriteTranslatorBatch.class);

    @Override
    public void translateNode(AvroIO.Write.Bound<T> transform, FlinkBatchTranslationContext context) {
      DataSet<T> inputDataSet = context.getInputDataSet(context.getInput(transform));
      String filenamePrefix = transform.getFilenamePrefix();
      String filenameSuffix = transform.getFilenameSuffix();
      int numShards = transform.getNumShards();
      String shardNameTemplate = transform.getShardNameTemplate();

      // TODO: Implement these. We need Flink support for this.
      LOG.warn("Translation of TextIO.Write.filenameSuffix not yet supported. Is: {}.",
          filenameSuffix);
      LOG.warn("Translation of TextIO.Write.shardNameTemplate not yet supported. Is: {}.", shardNameTemplate);

      // This is super hacky, but unfortunately we cannot get the type otherwise
      Class<T> extractedAvroType;
      try {
        Field typeField = transform.getClass().getDeclaredField("type");
        typeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Class<T> avroType = (Class<T>) typeField.get(transform);
        extractedAvroType = avroType;
      } catch (NoSuchFieldException | IllegalAccessException e) {
        // we know that the field is there and it is accessible
        throw new RuntimeException("Could not access type from AvroIO.Bound", e);
      }

      DataSink<T> dataSink = inputDataSet.output(new AvroOutputFormat<>(new Path
          (filenamePrefix), extractedAvroType));

      if (numShards > 0) {
        dataSink.setParallelism(numShards);
      }
    }
  }

  private static class TextIOReadTranslatorBatch implements FlinkBatchPipelineTranslator.BatchTransformTranslator<TextIO.Read.Bound<String>> {
    private static final Logger LOG = LoggerFactory.getLogger(TextIOReadTranslatorBatch.class);

    @Override
    public void translateNode(TextIO.Read.Bound<String> transform, FlinkBatchTranslationContext context) {
      String path = transform.getFilepattern();
      String name = transform.getName();

      TextIO.CompressionType compressionType = transform.getCompressionType();
      boolean needsValidation = transform.needsValidation();

      // TODO: Implement these. We need Flink support for this.
      LOG.warn("Translation of TextIO.CompressionType not yet supported. Is: {}.", compressionType);
      LOG.warn("Translation of TextIO.Read.needsValidation not yet supported. Is: {}.", needsValidation);

      PValue output = context.getOutput(transform);

      TypeInformation<String> typeInformation = context.getTypeInfo(output);
      DataSource<String> source = new DataSource<>(context.getExecutionEnvironment(), new TextInputFormat(new Path(path)), typeInformation, name);

      context.setOutputDataSet(output, source);
    }
  }

  private static class TextIOWriteTranslatorBatch<T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<TextIO.Write.Bound<T>> {
    private static final Logger LOG = LoggerFactory.getLogger(TextIOWriteTranslatorBatch.class);

    @Override
    public void translateNode(TextIO.Write.Bound<T> transform, FlinkBatchTranslationContext context) {
      PValue input = context.getInput(transform);
      DataSet<T> inputDataSet = context.getInputDataSet(input);

      String filenamePrefix = transform.getFilenamePrefix();
      String filenameSuffix = transform.getFilenameSuffix();
      boolean needsValidation = transform.needsValidation();
      int numShards = transform.getNumShards();
      String shardNameTemplate = transform.getShardNameTemplate();

      // TODO: Implement these. We need Flink support for this.
      LOG.warn("Translation of TextIO.Write.needsValidation not yet supported. Is: {}.", needsValidation);
      LOG.warn("Translation of TextIO.Write.filenameSuffix not yet supported. Is: {}.", filenameSuffix);
      LOG.warn("Translation of TextIO.Write.shardNameTemplate not yet supported. Is: {}.", shardNameTemplate);

      //inputDataSet.print();
      DataSink<T> dataSink = inputDataSet.writeAsText(filenamePrefix);

      if (numShards > 0) {
        dataSink.setParallelism(numShards);
      }
    }
  }

  private static class ConsoleIOWriteTranslatorBatch implements FlinkBatchPipelineTranslator.BatchTransformTranslator<ConsoleIO.Write.Bound> {
    @Override
    public void translateNode(ConsoleIO.Write.Bound transform, FlinkBatchTranslationContext context) {
      PValue input = context.getInput(transform);
      DataSet<?> inputDataSet = context.getInputDataSet(input);
      inputDataSet.printOnTaskManager(transform.getName());
    }
  }

  private static class WriteSinkTranslatorBatch<T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<Write.Bound<T>> {

    @Override
    public void translateNode(Write.Bound<T> transform, FlinkBatchTranslationContext context) {
      String name = transform.getName();
      PValue input = context.getInput(transform);
      DataSet<T> inputDataSet = context.getInputDataSet(input);

      inputDataSet.output(new SinkOutputFormat<>(transform, context.getPipelineOptions())).name(name);
    }
  }

  /**
   * Translates a GroupByKey while ignoring window assignments. Current ignores windows.
   */
  private static class GroupByKeyTranslatorBatch<K, V> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<GroupByKey<K, V>> {

    @Override
    public void translateNode(GroupByKey<K, V> transform, FlinkBatchTranslationContext context) {
      DataSet<KV<K, V>> inputDataSet = context.getInputDataSet(context.getInput(transform));
      GroupReduceFunction<KV<K, V>, KV<K, Iterable<V>>> groupReduceFunction = new FlinkKeyedListAggregationFunction<>();

      TypeInformation<KV<K, Iterable<V>>> typeInformation = context.getTypeInfo(context.getOutput(transform));

      Grouping<KV<K, V>> grouping = new UnsortedGrouping<>(inputDataSet, new Keys.ExpressionKeys<>(new String[]{"key"}, inputDataSet.getType()));

      GroupReduceOperator<KV<K, V>, KV<K, Iterable<V>>> outputDataSet =
          new GroupReduceOperator<>(grouping, typeInformation, groupReduceFunction, transform.getName());

      context.setOutputDataSet(context.getOutput(transform), outputDataSet);
    }
  }

  private static class CombinePerKeyTranslatorBatch<K, VI, VA, VO> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<Combine.PerKey<K, VI, VO>> {

    @Override
    public void translateNode(Combine.PerKey<K, VI, VO> transform, FlinkBatchTranslationContext context) {
      DataSet<KV<K, VI>> inputDataSet = context.getInputDataSet(context.getInput(transform));

      @SuppressWarnings("unchecked")
      Combine.KeyedCombineFn<K, VI, VA, VO> keyedCombineFn = (Combine.KeyedCombineFn<K, VI, VA, VO>) transform.getFn();

      KvCoder<K, VI> inputCoder = (KvCoder<K, VI>) context.getInput(transform).getCoder();

      Coder<VA> accumulatorCoder =
          null;
      try {
        accumulatorCoder = keyedCombineFn.getAccumulatorCoder(context.getInput(transform).getPipeline().getCoderRegistry(), inputCoder.getKeyCoder(), inputCoder.getValueCoder());
      } catch (CannotProvideCoderException e) {
        e.printStackTrace();
        // TODO
      }

      TypeInformation<KV<K, VI>> kvCoderTypeInformation = new KvCoderTypeInformation<>(inputCoder);
      TypeInformation<KV<K, VA>> partialReduceTypeInfo = new KvCoderTypeInformation<>(KvCoder.of(inputCoder.getKeyCoder(), accumulatorCoder));

      Grouping<KV<K, VI>> inputGrouping = new UnsortedGrouping<>(inputDataSet, new Keys.ExpressionKeys<>(new String[]{"key"}, kvCoderTypeInformation));

      FlinkPartialReduceFunction<K, VI, VA> partialReduceFunction = new FlinkPartialReduceFunction<>(keyedCombineFn);

      // Partially GroupReduce the values into the intermediate format VA (combine)
      GroupCombineOperator<KV<K, VI>, KV<K, VA>> groupCombine =
          new GroupCombineOperator<>(inputGrouping, partialReduceTypeInfo, partialReduceFunction,
              "GroupCombine: " + transform.getName());

      // Reduce fully to VO
      GroupReduceFunction<KV<K, VA>, KV<K, VO>> reduceFunction = new FlinkReduceFunction<>(keyedCombineFn);

      TypeInformation<KV<K, VO>> reduceTypeInfo = context.getTypeInfo(context.getOutput(transform));

      Grouping<KV<K, VA>> intermediateGrouping = new UnsortedGrouping<>(groupCombine, new Keys.ExpressionKeys<>(new String[]{"key"}, groupCombine.getType()));

      // Fully reduce the values and create output format VO
      GroupReduceOperator<KV<K, VA>, KV<K, VO>> outputDataSet =
          new GroupReduceOperator<>(intermediateGrouping, reduceTypeInfo, reduceFunction, transform.getName());

      context.setOutputDataSet(context.getOutput(transform), outputDataSet);
    }
  }

//  private static class CombineGroupedValuesTranslator<K, VI, VO> implements FlinkPipelineTranslator.TransformTranslator<Combine.GroupedValues<K, VI, VO>> {
//
//    @Override
//    public void translateNode(Combine.GroupedValues<K, VI, VO> transform, TranslationContext context) {
//      DataSet<KV<K, VI>> inputDataSet = context.getInputDataSet(transform.getInput());
//
//      Combine.KeyedCombineFn<? super K, ? super VI, ?, VO> keyedCombineFn = transform.getFn();
//
//      GroupReduceFunction<KV<K, VI>, KV<K, VO>> groupReduceFunction = new FlinkCombineFunction<>(keyedCombineFn);
//
//      TypeInformation<KV<K, VO>> typeInformation = context.getTypeInfo(transform.getOutput());
//
//      Grouping<KV<K, VI>> grouping = new UnsortedGrouping<>(inputDataSet, new Keys.ExpressionKeys<>(new String[]{""}, inputDataSet.getType()));
//
//      GroupReduceOperator<KV<K, VI>, KV<K, VO>> outputDataSet =
//          new GroupReduceOperator<>(grouping, typeInformation, groupReduceFunction, transform.getName());
//      context.setOutputDataSet(transform.getOutput(), outputDataSet);
//    }
//  }

  private static class ParDoBoundTranslatorBatch<IN, OUT> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<ParDo.Bound<IN, OUT>> {
    private static final Logger LOG = LoggerFactory.getLogger(ParDoBoundTranslatorBatch.class);

    @Override
    public void translateNode(ParDo.Bound<IN, OUT> transform, FlinkBatchTranslationContext context) {
      DataSet<IN> inputDataSet = context.getInputDataSet(context.getInput(transform));

      final DoFn<IN, OUT> doFn = transform.getFn();

      TypeInformation<OUT> typeInformation = context.getTypeInfo(context.getOutput(transform));

      FlinkDoFnFunction<IN, OUT> doFnWrapper = new FlinkDoFnFunction<>(doFn, context.getPipelineOptions());
      MapPartitionOperator<IN, OUT> outputDataSet = new MapPartitionOperator<>(inputDataSet, typeInformation, doFnWrapper, transform.getName());

      transformSideInputs(transform.getSideInputs(), outputDataSet, context);

      context.setOutputDataSet(context.getOutput(transform), outputDataSet);
    }
  }

  private static class ParDoBoundMultiTranslatorBatch<IN, OUT> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<ParDo.BoundMulti<IN, OUT>> {
    private static final Logger LOG = LoggerFactory.getLogger(ParDoBoundMultiTranslatorBatch.class);

    @Override
    public void translateNode(ParDo.BoundMulti<IN, OUT> transform, FlinkBatchTranslationContext context) {
      DataSet<IN> inputDataSet = context.getInputDataSet(context.getInput(transform));

      final DoFn<IN, OUT> doFn = transform.getFn();

      Map<TupleTag<?>, PCollection<?>> outputs = context.getOutput(transform).getAll();

      Map<TupleTag<?>, Integer> outputMap = Maps.newHashMap();
      // put the main output at index 0, FlinkMultiOutputDoFnFunction also expects this
      outputMap.put(transform.getMainOutputTag(), 0);
      int count = 1;
      for (TupleTag<?> tag: outputs.keySet()) {
        if (!outputMap.containsKey(tag)) {
          outputMap.put(tag, count++);
        }
      }

      // collect all output Coders and create a UnionCoder for our tagged outputs
      List<Coder<?>> outputCoders = Lists.newArrayList();
      for (PCollection<?> coll: outputs.values()) {
        outputCoders.add(coll.getCoder());
      }

      UnionCoder unionCoder = UnionCoder.of(outputCoders);

      @SuppressWarnings("unchecked")
      TypeInformation<RawUnionValue> typeInformation = new CoderTypeInformation<>(unionCoder);

      @SuppressWarnings("unchecked")
      FlinkMultiOutputDoFnFunction<IN, OUT> doFnWrapper = new FlinkMultiOutputDoFnFunction(doFn, context.getPipelineOptions(), outputMap);
      MapPartitionOperator<IN, RawUnionValue> outputDataSet = new MapPartitionOperator<>(inputDataSet, typeInformation, doFnWrapper, transform.getName());

      transformSideInputs(transform.getSideInputs(), outputDataSet, context);

      for (Map.Entry<TupleTag<?>, PCollection<?>> output: outputs.entrySet()) {
        TypeInformation<Object> outputType = context.getTypeInfo(output.getValue());
        int outputTag = outputMap.get(output.getKey());
        FlinkMultiOutputPruningFunction<Object> pruningFunction = new FlinkMultiOutputPruningFunction<>(outputTag);
        FlatMapOperator<RawUnionValue, Object> pruningOperator = new
            FlatMapOperator<>(outputDataSet, outputType,
            pruningFunction, output.getValue().getName());
        context.setOutputDataSet(output.getValue(), pruningOperator);

      }
    }
  }

  private static class FlattenPCollectionTranslatorBatch<T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<Flatten.FlattenPCollectionList<T>> {

    @Override
    public void translateNode(Flatten.FlattenPCollectionList<T> transform, FlinkBatchTranslationContext context) {
      List<PCollection<T>> allInputs = context.getInput(transform).getAll();
      DataSet<T> result = null;
      for(PCollection<T> collection : allInputs) {
        DataSet<T> current = context.getInputDataSet(collection);
        if (result == null) {
          result = current;
        } else {
          result = result.union(current);
        }
      }
      context.setOutputDataSet(context.getOutput(transform), result);
    }
  }

  private static class CreatePCollectionViewTranslatorBatch<R, T> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<View.CreatePCollectionView<R, T>> {
    @Override
    public void translateNode(View.CreatePCollectionView<R, T> transform, FlinkBatchTranslationContext context) {
      DataSet<T> inputDataSet = context.getInputDataSet(context.getInput(transform));
      PCollectionView<T> input = transform.apply(null);
      context.setSideInputDataSet(input, inputDataSet);
    }
  }

  private static class CreateTranslatorBatch<OUT> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<Create.Values<OUT>> {

    @Override
    public void translateNode(Create.Values<OUT> transform, FlinkBatchTranslationContext context) {
      TypeInformation<OUT> typeInformation = context.getOutputTypeInfo();
      Iterable<OUT> elements = transform.getElements();

      // we need to serialize the elements to byte arrays, since they might contain
      // elements that are not serializable by Java serialization. We deserialize them
      // in the FlatMap function using the Coder.

      List<byte[]> serializedElements = Lists.newArrayList();
      Coder<OUT> coder = context.getOutput(transform).getCoder();
      for (OUT element: elements) {
        ByteArrayOutputStream bao = new ByteArrayOutputStream();
        try {
          coder.encode(element, bao, Coder.Context.OUTER);
          serializedElements.add(bao.toByteArray());
        } catch (IOException e) {
          throw new RuntimeException("Could not serialize Create elements using Coder: " + e);
        }
      }

      DataSet<Integer> initDataSet = context.getExecutionEnvironment().fromElements(1);
      FlinkCreateFunction<Integer, OUT> flatMapFunction = new FlinkCreateFunction<>(serializedElements, coder);
      FlatMapOperator<Integer, OUT> outputDataSet = new FlatMapOperator<>(initDataSet, typeInformation, flatMapFunction, transform.getName());

      context.setOutputDataSet(context.getOutput(transform), outputDataSet);
    }
  }

  private static void transformSideInputs(List<PCollectionView<?>> sideInputs,
                                          MapPartitionOperator<?, ?> outputDataSet,
                                          FlinkBatchTranslationContext context) {
    // get corresponding Flink broadcast DataSets
    for(PCollectionView<?> input : sideInputs) {
      DataSet<?> broadcastSet = context.getSideInputDataSet(input);
      outputDataSet.withBroadcastSet(broadcastSet, input.getTagInternal().getId());
    }
  }

// Disabled because it depends on a pending pull request to the DataFlowSDK
  /**
   * Special composite transform translator. Only called if the CoGroup is two dimensional.
   * @param <K>
   */
  private static class CoGroupByKeyTranslatorBatch<K, V1, V2> implements FlinkBatchPipelineTranslator.BatchTransformTranslator<CoGroupByKey<K>> {

    @Override
    public void translateNode(CoGroupByKey<K> transform, FlinkBatchTranslationContext context) {
      KeyedPCollectionTuple<K> input = context.getInput(transform);

      CoGbkResultSchema schema = input.getCoGbkResultSchema();
      List<KeyedPCollectionTuple.TaggedKeyedPCollection<K, ?>> keyedCollections = input.getKeyedCollections();

      KeyedPCollectionTuple.TaggedKeyedPCollection<K, ?> taggedCollection1 = keyedCollections.get(0);
      KeyedPCollectionTuple.TaggedKeyedPCollection<K, ?> taggedCollection2 = keyedCollections.get(1);

      TupleTag<?> tupleTag1 = taggedCollection1.getTupleTag();
      TupleTag<?> tupleTag2 = taggedCollection2.getTupleTag();

      PCollection<? extends KV<K, ?>> collection1 = taggedCollection1.getCollection();
      PCollection<? extends KV<K, ?>> collection2 = taggedCollection2.getCollection();

      DataSet<KV<K,V1>> inputDataSet1 = context.getInputDataSet(collection1);
      DataSet<KV<K,V2>> inputDataSet2 = context.getInputDataSet(collection2);

      TypeInformation<KV<K,CoGbkResult>> typeInfo = context.getOutputTypeInfo();

      FlinkCoGroupKeyedListAggregator<K,V1,V2> aggregator = new FlinkCoGroupKeyedListAggregator<>(schema, tupleTag1, tupleTag2);

      Keys.ExpressionKeys<KV<K,V1>> keySelector1 = new Keys.ExpressionKeys<>(new String[]{"key"}, inputDataSet1.getType());
      Keys.ExpressionKeys<KV<K,V2>> keySelector2 = new Keys.ExpressionKeys<>(new String[]{"key"}, inputDataSet2.getType());

      DataSet<KV<K, CoGbkResult>> out = new CoGroupOperator<>(inputDataSet1, inputDataSet2,
                                  keySelector1, keySelector2,
                                                          aggregator, typeInfo, null, transform.getName());
      context.setOutputDataSet(context.getOutput(transform), out);
    }
  }

  // --------------------------------------------------------------------------------------------
  //  Miscellaneous
  // --------------------------------------------------------------------------------------------

  private FlinkBatchTransformTranslators() {}
}
