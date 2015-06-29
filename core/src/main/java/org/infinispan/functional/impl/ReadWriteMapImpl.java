package org.infinispan.functional.impl;

import org.infinispan.commands.functional.ReadWriteKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyValueCommand;
import org.infinispan.commands.functional.ReadWriteManyCommand;
import org.infinispan.commands.functional.ReadWriteManyEntriesCommand;
import org.infinispan.commons.api.functional.EntryView.ReadWriteEntryView;
import org.infinispan.commons.api.functional.FunctionalMap.ReadWriteMap;
import org.infinispan.commons.api.functional.Listeners.ReadWriteListeners;
import org.infinispan.commons.api.functional.Param;
import org.infinispan.commons.api.functional.Param.WaitMode;
import org.infinispan.commons.api.functional.Traversable;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.commons.util.CloseableIteratorSet;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.context.InvocationContext;
import org.infinispan.filter.AcceptAllKeyValueFilter;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.infinispan.functional.impl.WaitModes.withWaitFuture;
import static org.infinispan.functional.impl.WaitModes.withWaitTraversable;

public final class ReadWriteMapImpl<K, V> extends AbstractFunctionalMap<K, V> implements ReadWriteMap<K, V> {
   private static final Log log = LogFactory.getLog(ReadWriteMapImpl.class);
   private final Params params;

   private ReadWriteMapImpl(Params params, FunctionalMapImpl<K, V> functionalMap) {
      super(functionalMap);
      this.params = params;
   }

   public static <K, V> ReadWriteMap<K, V> create(FunctionalMapImpl<K, V> functionalMap) {
      return new ReadWriteMapImpl<>(Params.from(functionalMap.params.params), functionalMap);
   }

   private static <K, V> ReadWriteMap<K, V> create(Params params, FunctionalMapImpl<K, V> functionalMap) {
      return new ReadWriteMapImpl<>(params, functionalMap);
   }

   @Override
   public <R> CompletableFuture<R> eval(K key, Function<ReadWriteEntryView<K, V>, R> f) {
      log.tracef("Invoked eval(k=%s, %s)%n", key, params);
      Param<WaitMode> waitMode = params.get(WaitMode.ID);
      ReadWriteKeyCommand cmd = fmap.cmdFactory().buildReadWriteKeyCommand(fmap.notifier, key, f);
      InvocationContext ctx = fmap.invCtxFactory().createInvocationContext(true, 1);
      return withWaitFuture(waitMode, fmap.asyncExec(), () -> (R) fmap.chain().invoke(ctx, cmd));
   }

   @Override
   public <R> CompletableFuture<R> eval(K key, V value, BiFunction<V, ReadWriteEntryView<K, V>, R> f) {
      log.tracef("Invoked eval(k=%s, v=%s, %s)%n", key, value, params);
      Param<WaitMode> waitMode = params.get(WaitMode.ID);
      ReadWriteKeyValueCommand cmd = fmap.cmdFactory().buildReadWriteKeyValueCommand(fmap.notifier, key, value, f);
      InvocationContext ctx = fmap.invCtxFactory().createInvocationContext(true, 1);
      return withWaitFuture(waitMode, fmap.asyncExec(), () -> (R) fmap.chain().invoke(ctx, cmd));
   }

   @Override
   public <R> Traversable<R> evalMany(Map<? extends K, ? extends V> entries, BiFunction<V, ReadWriteEntryView<K, V>, R> f) {
      log.tracef("Invoked evalMany(entries=%s, %s)%n", entries, params);
      Param<WaitMode> waitMode = params.get(WaitMode.ID);
      ReadWriteManyEntriesCommand cmd = fmap.cmdFactory().buildReadWriteManyEntriesCommand(fmap.notifier, entries, f);
      InvocationContext ctx = fmap.invCtxFactory().createInvocationContext(true, entries.size());
      return withWaitTraversable(waitMode, () -> (Stream<R>) fmap.chain().invoke(ctx, cmd));
   }

   @Override
   public <R> Traversable<R> evalMany(Set<? extends K> keys, Function<ReadWriteEntryView<K, V>, R> f) {
      log.tracef("Invoked evalMany(keys=%s, %s)%n", keys, params);
      Param<WaitMode> waitMode = params.get(WaitMode.ID);
      ReadWriteManyCommand cmd = fmap.cmdFactory().buildReadWriteManyCommand(fmap.notifier, keys, f);
      InvocationContext ctx = fmap.invCtxFactory().createInvocationContext(true, keys.size());
      return withWaitTraversable(waitMode, () -> (Stream<R>) fmap.chain().invoke(ctx, cmd));
   }

   @Override
   public <R> Traversable<R> evalAll(Function<ReadWriteEntryView<K, V>, R> f) {
      log.tracef("Invoked evalAll(%s)%n", params);
      Param<WaitMode> waitMode = params.get(WaitMode.ID);
      CloseableIteratorSet<K> keys = fmap.cache.keySet();
      ReadWriteManyCommand cmd = fmap.cmdFactory().buildReadWriteManyCommand(fmap.notifier, keys, f);
      InvocationContext ctx = fmap.invCtxFactory().createInvocationContext(true, keys.size());
      return withWaitTraversable(waitMode, () -> (Stream<R>) fmap.chain().invoke(ctx, cmd));
   }

   @Override
   public ReadWriteListeners<K, V> listeners() {
      return fmap.notifier;
   }

   @Override
   public ReadWriteMap<K, V> withParams(Param<?>... ps) {
      if (ps == null || ps.length == 0)
         return this;

      if (params.containsAll(ps))
         return this; // We already have all specified params

      return create(params.addAll(ps), fmap);
   }

}
