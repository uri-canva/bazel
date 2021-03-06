// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Registry class for handling {@link ObjectCodec} mappings. Codecs are indexed by {@link String}
 * classifiers and assigned deterministic numeric identifiers for more compact on-the-wire
 * representation if desired.
 */
public class ObjectCodecRegistry {

  static Builder newBuilder() {
    return new Builder();
  }

  private final boolean allowDefaultCodec;

  private final ImmutableMap<Class<?>, CodecDescriptor> classMappedCodecs;
  private final ImmutableList<CodecDescriptor> tagMappedCodecs;

  private final int constantsStartTag;
  private final IdentityHashMap<Object, Integer> constantsMap;
  private final ImmutableList<Object> constants;

  /** This is sorted, but we need index-based access. */
  private final ImmutableList<String> classNames;

  private final IdentityHashMap<String, Supplier<CodecDescriptor>> dynamicCodecs;

  private ObjectCodecRegistry(
      ImmutableSet<ObjectCodec<?>> memoizingCodecs,
      ImmutableList<Object> constants,
      ImmutableSortedSet<String> classNames,
      boolean allowDefaultCodec) {
    this.allowDefaultCodec = allowDefaultCodec;

    int nextTag = 1; // 0 is reserved for null.
    ImmutableMap.Builder<Class<?>, CodecDescriptor> memoizingCodecsBuilder =
        ImmutableMap.builderWithExpectedSize(memoizingCodecs.size());
    ImmutableList.Builder<CodecDescriptor> tagMappedMemoizingCodecsBuilder =
        ImmutableList.builderWithExpectedSize(memoizingCodecs.size());
    nextTag =
        processCodecs(
            memoizingCodecs, nextTag, tagMappedMemoizingCodecsBuilder, memoizingCodecsBuilder);

    this.classMappedCodecs = memoizingCodecsBuilder.build();
    this.tagMappedCodecs = tagMappedMemoizingCodecsBuilder.build();

    constantsStartTag = nextTag;
    constantsMap = new IdentityHashMap<>();
    for (Object constant : constants) {
      constantsMap.put(constant, nextTag++);
    }
    this.constants = constants;

    this.classNames = classNames.asList();
    this.dynamicCodecs = createDynamicCodecs(classNames, nextTag);
  }

  public CodecDescriptor getCodecDescriptorForObject(Object obj)
      throws SerializationException.NoCodecException {
    Class<?> type = obj.getClass();
    CodecDescriptor descriptor = getCodecDescriptor(type);
    if (descriptor != null) {
      return descriptor;
    }
    if (!allowDefaultCodec) {
      throw new SerializationException.NoCodecException(
          "No codec available for " + type + " and default fallback disabled");
    }
    if (obj instanceof Enum) {
      // Enums must be serialized using declaring class.
      type = ((Enum) obj).getDeclaringClass();
    }
    return getDynamicCodecDescriptor(type.getName());
  }

  /**
   * Returns a {@link CodecDescriptor} for the given type or null if none found.
   *
   * <p>Also checks if there are codecs for a superclass of the given type.
   */
  private @Nullable CodecDescriptor getCodecDescriptor(Class<?> type) {
    // TODO(blaze-team): consider caching this traversal.
    for (Class<?> nextType = type; nextType != null; nextType = nextType.getSuperclass()) {
      CodecDescriptor result = classMappedCodecs.get(nextType);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  @Nullable
  Object maybeGetConstantByTag(int tag) {
    return tag < constantsStartTag || tag - constantsStartTag >= constants.size()
        ? null
        : constants.get(tag - constantsStartTag);
  }

  @Nullable
  Integer maybeGetTagForConstant(Object object) {
    return constantsMap.get(object);
  }

  /** Returns the {@link CodecDescriptor} associated with the supplied tag. */
  public CodecDescriptor getCodecDescriptorByTag(int tag)
      throws SerializationException.NoCodecException {
    int tagOffset = tag - 1; // 0 reserved for null
    if (tagOffset < 0) {
      throw new SerializationException.NoCodecException("No codec available for tag " + tag);
    }
    if (tagOffset < tagMappedCodecs.size()) {
      return tagMappedCodecs.get(tagOffset);
    }

    tagOffset -= tagMappedCodecs.size();
    tagOffset -= constants.size();
    if (!allowDefaultCodec || tagOffset < 0 || tagOffset >= classNames.size()) {
      throw new SerializationException.NoCodecException("No codec available for tag " + tag);
    }
    return getDynamicCodecDescriptor(classNames.get(tagOffset));
  }

  /**
   * Creates a builder using the current contents of this registry.
   *
   * <p>This is much more efficient than scanning multiple times.
   */
  @VisibleForTesting
  public Builder getBuilder() {
    Builder builder = newBuilder();
    builder.setAllowDefaultCodec(allowDefaultCodec);
    for (Map.Entry<Class<?>, CodecDescriptor> entry : classMappedCodecs.entrySet()) {
      builder.add(entry.getValue().getCodec());
    }

    for (Object constant : constants) {
      builder.addConstant(constant);
    }

    for (String className : classNames) {
      builder.addClassName(className);
    }
    return builder;
  }

  ImmutableList<String> classNames() {
    return classNames;
  }

  /** Describes encoding logic. */
  interface CodecDescriptor {
    void serialize(SerializationContext context, Object obj, CodedOutputStream codedOut)
        throws IOException, SerializationException;

    Object deserialize(DeserializationContext context, CodedInputStream codedIn)
        throws IOException, SerializationException;

    /**
     * Unique identifier for the associated codec.
     *
     * <p>Intended to be used as a compact on-the-wire representation of an encoded object's type.
     *
     * <p>Returns a value ≥ 1.
     *
     * <p>0 is a special tag representing null while negative numbers are reserved for
     * backreferences.
     */
    int getTag();

    /** Returns the underlying codec. */
    ObjectCodec<?> getCodec();
  }

  private static class TypedCodecDescriptor<T> implements CodecDescriptor {
    private final int tag;
    private final ObjectCodec<T> codec;

    private TypedCodecDescriptor(int tag, ObjectCodec<T> codec) {
      this.tag = tag;
      this.codec = codec;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void serialize(SerializationContext context, Object obj, CodedOutputStream codedOut)
        throws IOException, SerializationException {
      codec.serialize(context, (T) obj, codedOut);
    }

    @Override
    public T deserialize(DeserializationContext context, CodedInputStream codedIn)
        throws IOException, SerializationException {
      return codec.deserialize(context, codedIn);
    }

    @Override
    public int getTag() {
      return tag;
    }

    @Override
    public ObjectCodec<T> getCodec() {
      return codec;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("codec", codec).add("tag", tag).toString();
    }
  }

  /** Builder for {@link ObjectCodecRegistry}. */
  public static class Builder {
    private final ImmutableSet.Builder<ObjectCodec<?>> codecBuilder = ImmutableSet.builder();
    private final ImmutableList.Builder<Object> constantsBuilder = ImmutableList.builder();
    private final ImmutableSortedSet.Builder<String> classNames = ImmutableSortedSet.naturalOrder();
    private boolean allowDefaultCodec = true;

    public Builder add(ObjectCodec<?> codec) {
      codecBuilder.add(codec);
      return this;
    }

    /**
     * Set whether or not we allow fallback to java serialization when no matching codec is found.
     */
    public Builder setAllowDefaultCodec(boolean allowDefaultCodec) {
      this.allowDefaultCodec = allowDefaultCodec;
      return this;
    }

    public Builder addConstant(Object object) {
      constantsBuilder.add(object);
      return this;
    }

    public Builder addClassName(String className) {
      classNames.add(className);
      return this;
    }

    public ObjectCodecRegistry build() {
      return new ObjectCodecRegistry(
          codecBuilder.build(), constantsBuilder.build(), classNames.build(), allowDefaultCodec);
    }
  }

  private static int processCodecs(
      Iterable<? extends ObjectCodec<?>> memoizingCodecs,
      int nextTag,
      ImmutableList.Builder<CodecDescriptor> tagMappedCodecsBuilder,
      ImmutableMap.Builder<Class<?>, CodecDescriptor> codecsBuilder) {
    for (ObjectCodec<?> codec :
        ImmutableList.sortedCopyOf(
            Comparator.comparing(o -> o.getEncodedClass().getName()), memoizingCodecs)) {
      CodecDescriptor codecDescriptor = new TypedCodecDescriptor<>(nextTag++, codec);
      tagMappedCodecsBuilder.add(codecDescriptor);
      codecsBuilder.put(codec.getEncodedClass(), codecDescriptor);
      for (Class<?> otherClass : codec.additionalEncodedClasses()) {
        codecsBuilder.put(otherClass, codecDescriptor);
      }
    }
    return nextTag;
  }

  private static IdentityHashMap<String, Supplier<CodecDescriptor>> createDynamicCodecs(
      ImmutableSortedSet<String> classNames, int nextTag) {
    IdentityHashMap<String, Supplier<CodecDescriptor>> dynamicCodecs =
        new IdentityHashMap<>(classNames.size());
    for (String className : classNames) {
      int tag = nextTag++;
      dynamicCodecs.put(
          className, Suppliers.memoize(() -> createDynamicCodecDescriptor(tag, className)));
    }
    return dynamicCodecs;
  }

  /** For enums, this method must only be called for the declaring class. */
  private static CodecDescriptor createDynamicCodecDescriptor(int tag, String className) {
    try {
      Class<?> type = Class.forName(className);
      if (type.isEnum()) {
        return createCodecDescriptorForEnum(tag, type);
      }
      return new TypedCodecDescriptor<>(tag, new DynamicCodec(Class.forName(className)));
    } catch (ReflectiveOperationException e) {
      new SerializationException("Could not create codec for type: " + className, e)
          .printStackTrace();
      return null;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CodecDescriptor createCodecDescriptorForEnum(int tag, Class<?> enumType) {
    return new TypedCodecDescriptor(tag, new EnumCodec(enumType));
  }

  private CodecDescriptor getDynamicCodecDescriptor(String className)
      throws SerializationException.NoCodecException {
    Supplier<CodecDescriptor> supplier = dynamicCodecs.get(className);
    if (supplier == null) {
      throw new SerializationException.NoCodecException(
          "No default codec available for " + className);
    }
    CodecDescriptor descriptor = supplier.get();
    if (descriptor == null) {
      throw new SerializationException.NoCodecException(
          "There was a problem creating a codec for " + className + " check logs for details.");
    }
    return descriptor;
  }
}
