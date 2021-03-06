/*
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

package org.apache.ignite.internal.processors.cache;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.store.CacheStoreSessionListener;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.TransactionConfiguration;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.managers.communication.GridIoManager;
import org.apache.ignite.internal.managers.deployment.GridDeploymentManager;
import org.apache.ignite.internal.managers.discovery.GridDiscoveryManager;
import org.apache.ignite.internal.managers.eventstorage.GridEventStorageManager;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.jta.CacheJtaManagerAdapter;
import org.apache.ignite.internal.processors.cache.store.CacheStoreManager;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxManager;
import org.apache.ignite.internal.processors.cache.transactions.TransactionMetricsAdapter;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersionManager;
import org.apache.ignite.internal.processors.timeout.GridTimeoutProcessor;
import org.apache.ignite.internal.util.GridLongList;
import org.apache.ignite.internal.util.future.GridCompoundFuture;
import org.apache.ignite.internal.util.future.GridFinishedFuture;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.marshaller.Marshaller;
import org.jetbrains.annotations.Nullable;

/**
 * Shared context.
 */
@GridToStringExclude
public class GridCacheSharedContext<K, V> {
    /** Kernal context. */
    private GridKernalContext kernalCtx;

    /** Managers in starting order. */
    private List<GridCacheSharedManager<K, V>> mgrs = new LinkedList<>();

    /** Cache transaction manager. */
    private IgniteTxManager txMgr;

    /** JTA manager. */
    private CacheJtaManagerAdapter jtaMgr;

    /** Partition exchange manager. */
    private GridCachePartitionExchangeManager<K, V> exchMgr;

    /** Version manager. */
    private GridCacheVersionManager verMgr;

    /** Lock manager. */
    private GridCacheMvccManager mvccMgr;

    /** IO Manager. */
    private GridCacheIoManager ioMgr;

    /** Deployment manager. */
    private GridCacheDeploymentManager<K, V> depMgr;

    /** Affinity manager. */
    private CacheAffinitySharedManager affMgr;

    /** Cache contexts map. */
    private ConcurrentMap<Integer, GridCacheContext<K, V>> ctxMap;

    /** Tx metrics. */
    private volatile TransactionMetricsAdapter txMetrics;

    /** Store session listeners. */
    private Collection<CacheStoreSessionListener> storeSesLsnrs;

    /**
     * @param kernalCtx  Context.
     * @param txMgr Transaction manager.
     * @param verMgr Version manager.
     * @param mvccMgr MVCC manager.
     * @param depMgr Deployment manager.
     * @param exchMgr Exchange manager.
     * @param affMgr Affinity manager.
     * @param ioMgr IO manager.
     * @param jtaMgr JTA manager.
     * @param storeSesLsnrs Store session listeners.
     */
    public GridCacheSharedContext(
        GridKernalContext kernalCtx,
        IgniteTxManager txMgr,
        GridCacheVersionManager verMgr,
        GridCacheMvccManager mvccMgr,
        GridCacheDeploymentManager<K, V> depMgr,
        GridCachePartitionExchangeManager<K, V> exchMgr,
        CacheAffinitySharedManager<K, V> affMgr,
        GridCacheIoManager ioMgr,
        CacheJtaManagerAdapter jtaMgr,
        Collection<CacheStoreSessionListener> storeSesLsnrs
    ) {
        this.kernalCtx = kernalCtx;

        setManagers(mgrs, txMgr, jtaMgr, verMgr, mvccMgr, depMgr, exchMgr, affMgr, ioMgr);

        this.storeSesLsnrs = storeSesLsnrs;

        txMetrics = new TransactionMetricsAdapter();

        ctxMap = new ConcurrentHashMap<>();
    }

    /**
     * @param reconnectFut Reconnect future.
     * @throws IgniteCheckedException If failed.
     */
    void onDisconnected(IgniteFuture<?> reconnectFut) throws IgniteCheckedException {
        for (ListIterator<? extends GridCacheSharedManager<?, ?>> it = mgrs.listIterator(mgrs.size());
            it.hasPrevious();) {
            GridCacheSharedManager<?, ?> mgr = it.previous();

            mgr.onDisconnected(reconnectFut);

            if (restartOnDisconnect(mgr))
                mgr.onKernalStop(true);
        }

        for (ListIterator<? extends GridCacheSharedManager<?, ?>> it = mgrs.listIterator(mgrs.size()); it.hasPrevious();) {
            GridCacheSharedManager<?, ?> mgr = it.previous();

            if (restartOnDisconnect(mgr))
                mgr.stop(true);
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    void onReconnected() throws IgniteCheckedException {
        List<GridCacheSharedManager<K, V>> mgrs = new LinkedList<>();

        setManagers(mgrs, txMgr,
            jtaMgr,
            verMgr,
            mvccMgr,
            new GridCacheDeploymentManager<K, V>(),
            new GridCachePartitionExchangeManager<K, V>(),
            affMgr,
            ioMgr);

        this.mgrs = mgrs;

        for (GridCacheSharedManager<K, V> mgr : mgrs) {
            if (restartOnDisconnect(mgr))
                mgr.start(this);
        }

        for (GridCacheSharedManager<?, ?> mgr : mgrs)
            mgr.onKernalStart(true);
    }

    /**
     * @param mgr Manager.
     * @return {@code True} if manager is restarted cn reconnect.
     */
    private boolean restartOnDisconnect(GridCacheSharedManager<?, ?> mgr) {
        return mgr instanceof GridCacheDeploymentManager || mgr instanceof GridCachePartitionExchangeManager;
    }

    /**
     * @param mgrs Managers list.
     * @param txMgr Transaction manager.
     * @param verMgr Version manager.
     * @param mvccMgr MVCC manager.
     * @param depMgr Deployment manager.
     * @param exchMgr Exchange manager.
     * @param affMgr Affinity manager.
     * @param ioMgr IO manager.
     * @param jtaMgr JTA manager.
     */
    private void setManagers(List<GridCacheSharedManager<K, V>> mgrs,
        IgniteTxManager txMgr,
        CacheJtaManagerAdapter jtaMgr,
        GridCacheVersionManager verMgr,
        GridCacheMvccManager mvccMgr,
        GridCacheDeploymentManager<K, V> depMgr,
        GridCachePartitionExchangeManager<K, V> exchMgr,
        CacheAffinitySharedManager affMgr,
        GridCacheIoManager ioMgr) {
        this.mvccMgr = add(mgrs, mvccMgr);
        this.verMgr = add(mgrs, verMgr);
        this.txMgr = add(mgrs, txMgr);
        this.jtaMgr = add(mgrs, jtaMgr);
        this.depMgr = add(mgrs, depMgr);
        this.exchMgr = add(mgrs, exchMgr);
        this.affMgr = add(mgrs, affMgr);
        this.ioMgr = add(mgrs, ioMgr);
    }

    /**
     * Gets all cache contexts for local node.
     *
     * @return Collection of all cache contexts.
     */
    public Collection<GridCacheContext> cacheContexts() {
        return (Collection)ctxMap.values();
    }

    /**
     * @return Cache processor.
     */
    public GridCacheProcessor cache() {
        return kernalCtx.cache();
    }

    /**
     * Adds cache context to shared cache context.
     *
     * @param cacheCtx Cache context to add.
     * @throws IgniteCheckedException If cache ID conflict detected.
     */
    @SuppressWarnings("unchecked")
    public void addCacheContext(GridCacheContext cacheCtx) throws IgniteCheckedException {
        if (ctxMap.containsKey(cacheCtx.cacheId())) {
            GridCacheContext<K, V> existing = ctxMap.get(cacheCtx.cacheId());

            throw new IgniteCheckedException("Failed to start cache due to conflicting cache ID " +
                "(change cache name and restart grid) [cacheName=" + cacheCtx.name() +
                ", conflictingCacheName=" + existing.name() + ']');
        }

        ctxMap.put(cacheCtx.cacheId(), cacheCtx);
    }

    /**
     * @param cacheCtx Cache context to remove.
     */
    public void removeCacheContext(GridCacheContext cacheCtx) {
        int cacheId = cacheCtx.cacheId();

        ctxMap.remove(cacheId, cacheCtx);

        // Safely clean up the message listeners.
        ioMgr.removeHandlers(cacheId);
    }

    /**
     * Checks if cache context is closed.
     *
     * @param ctx Cache context to check.
     * @return {@code True} if cache context is closed.
     */
    public boolean closed(GridCacheContext ctx) {
        return !ctxMap.containsKey(ctx.cacheId());
    }

    /**
     * @return List of shared context managers in starting order.
     */
    public List<GridCacheSharedManager<K, V>> managers() {
        return mgrs;
    }

    /**
     * Gets cache context by cache ID.
     *
     * @param cacheId Cache ID.
     * @return Cache context.
     */
    public GridCacheContext<K, V> cacheContext(int cacheId) {
        return ctxMap.get(cacheId);
    }

    /**
     * @return Grid name.
     */
    public String gridName() {
        return kernalCtx.gridName();
    }

    /**
     * Gets transactions configuration.
     *
     * @return Transactions configuration.
     */
    public TransactionConfiguration txConfig() {
        return kernalCtx.config().getTransactionConfiguration();
    }

    /**
     * @return Timeout for initial map exchange before preloading. We make it {@code 4} times
     * bigger than network timeout by default.
     */
    public long preloadExchangeTimeout() {
        long t1 = gridConfig().getNetworkTimeout() * 4;
        long t2 = gridConfig().getNetworkTimeout() * gridConfig().getCacheConfiguration().length * 2;

        long timeout = Math.max(t1, t2);

        return timeout < 0 ? Long.MAX_VALUE : timeout;
    }

    /**
     * @return Deployment enabled flag.
     */
    public boolean deploymentEnabled() {
        return kernalContext().deploy().enabled();
    }

    /**
     * @return Data center ID.
     */
    public byte dataCenterId() {
        // Data center ID is same for all caches, so grab the first one.
        GridCacheContext<?, ?> cacheCtx = F.first(cacheContexts());

        return cacheCtx.dataCenterId();
    }

    /**
     * @return Transactional metrics adapter.
     */
    public TransactionMetricsAdapter txMetrics() {
        return txMetrics;
    }

    /**
     * Resets tx metrics.
     */
    public void resetTxMetrics() {
        txMetrics = new TransactionMetricsAdapter();
    }

    /**
     * @return Cache transaction manager.
     */
    public IgniteTxManager tm() {
        return txMgr;
    }

    /**
     * @return JTA manager.
     */
    public CacheJtaManagerAdapter jta() {
        return jtaMgr;
    }

    /**
     * @return Exchange manager.
     */
    public GridCachePartitionExchangeManager<K, V> exchange() {
        return exchMgr;
    }

    /**
     * @return Affinity manager.
     */
    public CacheAffinitySharedManager<K, V> affinity() {
        return affMgr;
    }

    /**
     * @return Lock order manager.
     */
    public GridCacheVersionManager versions() {
        return verMgr;
    }

    /**
     * @return Lock manager.
     */
    public GridCacheMvccManager mvcc() {
        return mvccMgr;
    }

    /**
     * @return IO manager.
     */
    public GridCacheIoManager io() {
        return ioMgr;
    }

    /**
     * @return Cache deployment manager.
     */
    public GridCacheDeploymentManager<K, V> deploy() {
        return depMgr;
    }

    /**
     * @return Marshaller.
     */
    public Marshaller marshaller() {
        return kernalCtx.config().getMarshaller();
    }

    /**
     * @return Grid configuration.
     */
    public IgniteConfiguration gridConfig() {
        return kernalCtx.config();
    }

    /**
     * @return Kernal context.
     */
    public GridKernalContext kernalContext() {
        return kernalCtx;
    }

    /**
     * @return Grid IO manager.
     */
    public GridIoManager gridIO() {
        return kernalCtx.io();
    }

    /**
     * @return Grid deployment manager.
     */
    public GridDeploymentManager gridDeploy() {
        return kernalCtx.deploy();
    }

    /**
     * @return Grid event storage manager.
     */
    public GridEventStorageManager gridEvents() {
        return kernalCtx.event();
    }

    /**
     * @return Discovery manager.
     */
    public GridDiscoveryManager discovery() {
        return kernalCtx.discovery();
    }

    /**
     * @return Timeout processor.
     */
    public GridTimeoutProcessor time() {
        return kernalCtx.timeout();
    }

    /**
     * @return Node ID.
     */
    public UUID localNodeId() {
        return kernalCtx.localNodeId();
    }

    /**
     * @return Local node.
     */
    public ClusterNode localNode() {
        return kernalCtx.discovery().localNode();
    }

    /**
     * @param nodeId Node ID.
     * @return Node or {@code null}.
     */
    @Nullable public ClusterNode node(UUID nodeId) {
        return kernalCtx.discovery().node(nodeId);
    }

    /**
     * Gets grid logger for given class.
     *
     * @param cls Class to get logger for.
     * @return IgniteLogger instance.
     */
    public IgniteLogger logger(Class<?> cls) {
        return kernalCtx.log(cls);
    }

    /**
     * @param category Category.
     * @return Logger.
     */
    public IgniteLogger logger(String category) {
        return kernalCtx.log(category);
    }

    /**
     * Waits for partition locks and transactions release.
     *
     * @param topVer Topology version.
     * @return {@code true} if waiting was successful.
     */
    @SuppressWarnings({"unchecked"})
    public IgniteInternalFuture<?> partitionReleaseFuture(AffinityTopologyVersion topVer) {
        GridCompoundFuture f = new GridCompoundFuture();

        f.add(mvcc().finishExplicitLocks(topVer));
        f.add(tm().finishTxs(topVer));
        f.add(mvcc().finishAtomicUpdates(topVer));

        f.markInitialized();

        return f;
    }

    /**
     * Gets ready future for the next affinity topology version (used in cases when a node leaves grid).
     *
     * @param curVer Current topology version (before a node left grid).
     * @return Ready future.
     */
    public IgniteInternalFuture<?> nextAffinityReadyFuture(AffinityTopologyVersion curVer) {
        if (curVer == null)
            return null;

        AffinityTopologyVersion nextVer = new AffinityTopologyVersion(curVer.topologyVersion() + 1);

        IgniteInternalFuture<?> fut = exchMgr.affinityReadyFuture(nextVer);

        return fut == null ? new GridFinishedFuture<>() : fut;
    }

    /**
     * @param tx Transaction to check.
     * @param activeCacheIds Active cache IDs.
     * @param cacheCtx Cache context.
     * @return Error message if transactions are incompatible.
     */
    @Nullable public String verifyTxCompatibility(IgniteInternalTx tx, GridLongList activeCacheIds,
        GridCacheContext<K, V> cacheCtx) {
        if (cacheCtx.systemTx() && !tx.system())
            return "system cache can be enlisted only in system transaction";

        if (!cacheCtx.systemTx() && tx.system())
            return "non-system cache can't be enlisted in system transaction";

        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = (int)activeCacheIds.get(i);

            GridCacheContext<K, V> activeCacheCtx = cacheContext(cacheId);

            if (cacheCtx.systemTx()) {
                if (activeCacheCtx.cacheId() != cacheCtx.cacheId())
                    return "system transaction can include only one cache";
            }

            CacheStoreManager store = cacheCtx.store();
            CacheStoreManager activeStore = activeCacheCtx.store();

            if (store.isLocal() != activeStore.isLocal())
                return "caches with local and non-local stores can't be enlisted in one transaction";

            if (store.isWriteBehind() != activeStore.isWriteBehind())
                return "caches with different write-behind setting can't be enlisted in one transaction";

            if (activeCacheCtx.deploymentEnabled() != cacheCtx.deploymentEnabled())
                return "caches with enabled and disabled deployment modes can't be enlisted in one transaction";

            // If local and write-behind validations passed, this must be true.
            assert store.isWriteToStoreFromDht() == activeStore.isWriteToStoreFromDht();
        }

        return null;
    }

    /**
     * @param ignore Transaction to ignore.
     * @return Not null topology version if current thread holds lock preventing topology change.
     */
    @Nullable public AffinityTopologyVersion lockedTopologyVersion(IgniteInternalTx ignore) {
        long threadId = Thread.currentThread().getId();

        AffinityTopologyVersion topVer = txMgr.lockedTopologyVersion(threadId, ignore);

        if (topVer == null)
            topVer = mvccMgr.lastExplicitLockTopologyVersion(threadId);

        return topVer;
    }

    /**
     * Nulling references to potentially leak-prone objects.
     */
    public void cleanup() {
        mvccMgr = null;

        mgrs.clear();
    }

    /**
     * @param tx Transaction to close.
     * @throws IgniteCheckedException If failed.
     */
    public void endTx(IgniteInternalTx tx) throws IgniteCheckedException {
        tx.txState().awaitLastFut(this);

        tx.close();
    }

    /**
     * @param tx Transaction to commit.
     * @return Commit future.
     */
    @SuppressWarnings("unchecked")
    public IgniteInternalFuture<IgniteInternalTx> commitTxAsync(IgniteInternalTx tx) {
        GridCacheContext ctx = tx.txState().singleCacheContext(this);

        if (ctx == null) {
            tx.txState().awaitLastFut(this);

            return tx.commitAsync();
        }
        else
            return ctx.cache().commitTxAsync(tx);
    }

    /**
     * @param tx Transaction to rollback.
     * @throws IgniteCheckedException If failed.
     * @return Rollback future.
     */
    public IgniteInternalFuture rollbackTxAsync(IgniteInternalTx tx) throws IgniteCheckedException {
        tx.txState().awaitLastFut(this);

        return tx.rollbackAsync();
    }

    /**
     * @return Store session listeners.
     */
    @Nullable public Collection<CacheStoreSessionListener> storeSessionListeners() {
        return storeSesLsnrs;
    }

    /**
     * @param mgrs Managers list.
     * @param mgr Manager to add.
     * @return Added manager.
     */
    @Nullable private <T extends GridCacheSharedManager<K, V>> T add(List<GridCacheSharedManager<K, V>> mgrs,
        @Nullable T mgr) {
        if (mgr != null)
            mgrs.add(mgr);

        return mgr;
    }

    /**
     * Reset thread-local context for transactional cache.
     */
    public void txContextReset() {
        mvccMgr.contextReset();
    }
}
