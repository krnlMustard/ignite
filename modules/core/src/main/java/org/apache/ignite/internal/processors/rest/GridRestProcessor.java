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

package org.apache.ignite.internal.processors.rest;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.*;
import org.apache.ignite.internal.processors.rest.client.message.*;
import org.apache.ignite.internal.processors.rest.handlers.*;
import org.apache.ignite.internal.processors.rest.handlers.cache.*;
import org.apache.ignite.internal.processors.rest.handlers.datastructures.*;
import org.apache.ignite.internal.processors.rest.handlers.task.*;
import org.apache.ignite.internal.processors.rest.handlers.top.*;
import org.apache.ignite.internal.processors.rest.handlers.version.*;
import org.apache.ignite.internal.processors.rest.protocols.tcp.*;
import org.apache.ignite.internal.processors.rest.request.*;
import org.apache.ignite.internal.processors.security.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.util.worker.*;
import org.apache.ignite.internal.visor.util.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.plugin.security.*;
import org.apache.ignite.plugin.security.SecurityException;
import org.jsr166.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.internal.processors.rest.GridRestResponse.*;
import static org.apache.ignite.plugin.security.SecuritySubjectType.*;

/**
 * Rest processor implementation.
 */
public class GridRestProcessor extends GridProcessorAdapter {
    /** HTTP protocol class name. */
    private static final String HTTP_PROTO_CLS =
        "org.apache.ignite.internal.processors.rest.protocols.http.jetty.GridJettyRestProtocol";

    /** */
    public static final byte[] ZERO_BYTES = new byte[0];

    /** Delay between sessions expire checks*/
    private static final int SES_EXPIRE_CHECK_DELAY = 1_000;

    /** TODO */
    private static final int SES_EXPERATION_TIME = 30_000;

    /** Protocols. */
    private final Collection<GridRestProtocol> protos = new ArrayList<>();

    /** Command handlers. */
    protected final Map<GridRestCommand, GridRestCommandHandler> handlers = new EnumMap<>(GridRestCommand.class);

    /** */
    private final CountDownLatch startLatch = new CountDownLatch(1);

    /** Busy lock. */
    private final GridSpinReadWriteLock busyLock = new GridSpinReadWriteLock();

    /** Workers count. */
    private final LongAdder8 workersCnt = new LongAdder8();

    /** SecurityContext map. */
    private ConcurrentMap<UUID, Session> clientId2Ses = new ConcurrentHashMap<>();

    /** SecurityContext map. */
    private ConcurrentMap<UUID, Session> sesTokId2Ses = new ConcurrentHashMap<>();

    /** Protocol handler. */
    private final GridRestProtocolHandler protoHnd = new GridRestProtocolHandler() {
        @Override public GridRestResponse handle(GridRestRequest req) throws IgniteCheckedException {
            return handleAsync(req).get();
        }

        @Override public IgniteInternalFuture<GridRestResponse> handleAsync(GridRestRequest req) {
            return handleAsync0(req);
        }
    };

    /**
     * @param req Request.
     * @return Future.
     */
    private IgniteInternalFuture<GridRestResponse> handleAsync0(final GridRestRequest req) {
        if (!busyLock.tryReadLock())
            return new GridFinishedFuture<>(
                new IgniteCheckedException("Failed to handle request (received request while stopping grid)."));

        try {
            final GridWorkerFuture<GridRestResponse> fut = new GridWorkerFuture<>();

            workersCnt.increment();

            GridWorker w = new GridWorker(ctx.gridName(), "rest-proc-worker", log) {
                @Override protected void body() {
                    try {
                        IgniteInternalFuture<GridRestResponse> res = handleRequest(req);

                        res.listen(new IgniteInClosure<IgniteInternalFuture<GridRestResponse>>() {
                            @Override public void apply(IgniteInternalFuture<GridRestResponse> f) {
                                try {
                                    fut.onDone(f.get());
                                }
                                catch (IgniteCheckedException e) {
                                    fut.onDone(e);
                                }
                            }
                        });
                    }
                    catch (Throwable e) {
                        if (e instanceof Error)
                            U.error(log, "Client request execution failed with error.", e);

                        fut.onDone(U.cast(e));

                        if (e instanceof Error)
                            throw e;
                    }
                    finally {
                        workersCnt.decrement();
                    }
                }
            };

            fut.setWorker(w);

            try {
                ctx.getRestExecutorService().execute(w);
            }
            catch (RejectedExecutionException e) {
                U.error(log, "Failed to execute worker due to execution rejection " +
                    "(increase upper bound on REST executor service). " +
                    "Will attempt to process request in the current thread instead.", e);

                w.run();
            }

            return fut;
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * @param req Request.
     * @return Future.
     */
    private IgniteInternalFuture<GridRestResponse> handleRequest(final GridRestRequest req) {
        if (startLatch.getCount() > 0) {
            try {
                startLatch.await();
            }
            catch (InterruptedException e) {
                return new GridFinishedFuture<>(new IgniteCheckedException("Failed to handle request " +
                    "(protocol handler was interrupted when awaiting grid start).", e));
            }
        }

        if (log.isDebugEnabled())
            log.debug("Received request from client: " + req);

        Session ses = null;

        if (ctx.security().enabled()) {
            try {
                ses = session(req);
            }
            catch (IgniteCheckedException e) {
                GridRestResponse res = new GridRestResponse(STATUS_FAILED, e.getMessage());

                return new GridFinishedFuture<>(res);
            }

            assert ses != null;

            req.clientId(ses.clientId);
            req.sessionToken(ses.sesTok());

            if (log.isDebugEnabled())
                log.debug("Next clientId and sessionToken were extracted from request: " +
                    "[clientId="+req.clientId()+", sessionToken="+Arrays.toString(req.sessionToken())+"]");

            try {
                if (ses.secCtx == null)
                    ses.secCtx = authenticate(req);

                authorize(req, ses.secCtx);
            }
            catch (SecurityException e) {
                assert ses.secCtx != null;

                GridRestResponse res = new GridRestResponse(STATUS_SECURITY_CHECK_FAILED, e.getMessage());

                res.sessionTokenBytes(ZERO_BYTES);

                return new GridFinishedFuture<>(res);
            }
            catch (IgniteCheckedException e) {
                return new GridFinishedFuture<>(new GridRestResponse(STATUS_AUTH_FAILED, e.getMessage()));
            }
        }

        interceptRequest(req);

        GridRestCommandHandler hnd = handlers.get(req.command());

        IgniteInternalFuture<GridRestResponse> res = hnd == null ? null : hnd.handleAsync(req);

        if (res == null)
            return new GridFinishedFuture<>(
                new IgniteCheckedException("Failed to find registered handler for command: " + req.command()));

        final SecurityContext subjCtx0 = ses == null ? null : ses.secCtx;

        return res.chain(new C1<IgniteInternalFuture<GridRestResponse>, GridRestResponse>() {
            @Override public GridRestResponse apply(IgniteInternalFuture<GridRestResponse> f) {
                GridRestResponse res;

                try {
                    res = f.get();
                }
                catch (Exception e) {
                    if (!X.hasCause(e, VisorClusterGroupEmptyException.class))
                        LT.error(log, e, "Failed to handle request: " + req.command());

                    if (log.isDebugEnabled())
                        log.debug("Failed to handle request [req=" + req + ", e=" + e + "]");

                    res = new GridRestResponse(STATUS_FAILED, e.getMessage());
                }

                assert res != null;

                if (ctx.security().enabled()) {
                    // TODO review. Why we update it here, not earlier?
//                    updateSession(req, subjCtx0);
                    res.sessionTokenBytes(req.sessionToken());
                }

                interceptResponse(res, req);

                return res;
            }
        });
    }

    /**
     * // TODO expiration
     * @param req Request.
     * @return Not null session.
     * @throws IgniteCheckedException If failed.
     */
    private Session session(final GridRestRequest req) throws IgniteCheckedException {
        final UUID clientId = req.clientId();
        final byte[] sesTok = req.sessionToken();

        if (F.isEmpty(sesTok) && clientId == null) {
            Session ses = new Session();
            ses.clientId = UUID.randomUUID();
            ses.sesTokId = UUID.randomUUID();

            clientId2Ses.put(ses.clientId, ses);
            sesTokId2Ses.put(ses.sesTokId, ses);

            return ses;
        }

        if (F.isEmpty(sesTok) && clientId != null) {
            Session ses = clientId2Ses.get(clientId);

            if (ses == null) { /** First request with this clientId */
                ses = new Session();
                ses.clientId = clientId;
                ses.sesTokId = UUID.randomUUID();

                Session curSes = clientId2Ses.putIfAbsent(ses.clientId, ses);

                if (curSes == null)
                    sesTokId2Ses.put(ses.sesTokId, ses);
                else {
                    boolean expired = curSes.checkExpirationAndTryUpdateLastTouchTime();

                    // curSes != null means that there was at least 2 parallel request
                    // by the same clientId (and we was not first).
                    // So it was approximately at the same time and oldSession can't be expired.
                    assert !expired;
                }

                return curSes == null ? ses : curSes;
            }
            else {
                if (ses.checkExpirationAndTryUpdateLastTouchTime())
                    return session(req);

                return ses;
            }
        }

        if (!F.isEmpty(sesTok) && clientId == null) {
            UUID sesTokId = U.bytesToUuid(sesTok, 0);

            Session ses = sesTokId2Ses.get(sesTokId);

            if (ses == null)
                throw new IgniteCheckedException("Failed to handle request. Unknown session token " +
                    "(maybe expired session). [sessionToken=" + U.byteArray2HexString(sesTok) + "]");

            if (ses.checkExpirationAndTryUpdateLastTouchTime())
                return session(req);

            return ses;
        }

        if (!F.isEmpty(sesTok) && clientId != null) {
            Session ses1 = sesTokId2Ses.get(U.bytesToUuid(sesTok, 0));
            Session ses2 = clientId2Ses.get(clientId);

            if (ses1 == null || ses2 == null || !ses1.equals(ses2))
                throw new IgniteCheckedException("Failed to handle request. " +
                    "Unsupported case (use one: clientId or session token)");

            if (ses1.checkExpirationAndTryUpdateLastTouchTime())
                return session(req);

            return ses1;
        }

        throw new IgniteCheckedException("Failed to handle request (Unreachable state).");
    }

    /**
     * @param ctx Context.
     */
    public GridRestProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        if (isRestEnabled()) {
            // Register handlers.
            addHandler(new GridCacheCommandHandler(ctx));
            addHandler(new GridTaskCommandHandler(ctx));
            addHandler(new GridTopologyCommandHandler(ctx));
            addHandler(new GridVersionCommandHandler(ctx));
            addHandler(new DataStructuresCommandHandler(ctx));

            // Start protocols.
            startTcpProtocol();
            startHttpProtocol();

            for (GridRestProtocol proto : protos) {
                Collection<IgniteBiTuple<String, Object>> props = proto.getProperties();

                if (props != null) {
                    for (IgniteBiTuple<String, Object> p : props) {
                        String key = p.getKey();

                        if (key == null)
                            continue;

                        if (ctx.hasNodeAttribute(key))
                            throw new IgniteCheckedException(
                                "Node attribute collision for attribute [processor=GridRestProcessor, attr=" + key + ']');

                        ctx.addNodeAttribute(key, p.getValue());
                    }
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws IgniteCheckedException {
        Thread sesExpirationThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while(!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(SES_EXPIRE_CHECK_DELAY);

                        for (Iterator<Map.Entry<UUID, Session>> iter = clientId2Ses.entrySet().iterator();
                            iter.hasNext();) {
                            Map.Entry<UUID, Session> e = iter.next();

                            if (e.getValue().checkExpiration())
                                iter.remove();
                        }

                        for (Iterator<Map.Entry<UUID, Session>> iter = sesTokId2Ses.entrySet().iterator();
                            iter.hasNext();) {
                            Map.Entry<UUID, Session> e = iter.next();

                            if (e.getValue().isExpired())
                                iter.remove();
                        }
                    }
                }
                catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "check-session-expired");

        sesExpirationThread.setDaemon(true);

        sesExpirationThread.start();

        if (isRestEnabled()) {
            for (GridRestProtocol proto : protos)
                proto.onKernalStart();

            startLatch.countDown();

            if (log.isDebugEnabled())
                log.debug("REST processor started.");
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("BusyWait")
    @Override public void onKernalStop(boolean cancel) {
        if (isRestEnabled()) {
            busyLock.writeLock();

            boolean interrupted = Thread.interrupted();

            while (workersCnt.sum() != 0) {
                try {
                    Thread.sleep(200);
                }
                catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted)
                Thread.currentThread().interrupt();

            for (GridRestProtocol proto : protos)
                proto.stop();

            // Safety.
            startLatch.countDown();

            if (log.isDebugEnabled())
                log.debug("REST processor stopped.");
        }
    }

    /**
     * Applies {@link ConnectorMessageInterceptor}
     * from {@link ConnectorConfiguration#getMessageInterceptor()} ()}
     * to all user parameters in the request.
     *
     * @param req Client request.
     */
    private void interceptRequest(GridRestRequest req) {
        ConnectorMessageInterceptor interceptor = config().getMessageInterceptor();

        if (interceptor == null)
            return;

        if (req instanceof GridRestCacheRequest) {
            GridRestCacheRequest req0 = (GridRestCacheRequest) req;

            req0.key(interceptor.onReceive(req0.key()));
            req0.value(interceptor.onReceive(req0.value()));
            req0.value2(interceptor.onReceive(req0.value2()));

            Map<Object, Object> oldVals = req0.values();

            if (oldVals != null) {
                Map<Object, Object> newVals = U.newHashMap(oldVals.size());

                for (Map.Entry<Object, Object> e : oldVals.entrySet())
                    newVals.put(interceptor.onReceive(e.getKey()), interceptor.onReceive(e.getValue()));

                req0.values(U.sealMap(newVals));
            }
        }
        else if (req instanceof GridRestTaskRequest) {
            GridRestTaskRequest req0 = (GridRestTaskRequest) req;

            List<Object> oldParams = req0.params();

            if (oldParams != null) {
                Collection<Object> newParams = new ArrayList<>(oldParams.size());

                for (Object o : oldParams)
                    newParams.add(interceptor.onReceive(o));

                req0.params(U.sealList(newParams));
            }
        }
    }

    /**
     * Applies {@link ConnectorMessageInterceptor} from
     * {@link ConnectorConfiguration#getMessageInterceptor()}
     * to all user objects in the response.
     *
     * @param res Response.
     * @param req Request.
     */
    private void interceptResponse(GridRestResponse res, GridRestRequest req) {
        ConnectorMessageInterceptor interceptor = config().getMessageInterceptor();

        if (interceptor != null && res.getResponse() != null) {
            switch (req.command()) {
                case CACHE_GET:
                case CACHE_GET_ALL:
                case CACHE_PUT:
                case CACHE_ADD:
                case CACHE_PUT_ALL:
                case CACHE_REMOVE:
                case CACHE_REMOVE_ALL:
                case CACHE_REPLACE:
                case ATOMIC_INCREMENT:
                case ATOMIC_DECREMENT:
                case CACHE_CAS:
                case CACHE_APPEND:
                case CACHE_PREPEND:
                    res.setResponse(interceptSendObject(res.getResponse(), interceptor));

                    break;

                case EXE:
                    if (res.getResponse() instanceof GridClientTaskResultBean) {
                        GridClientTaskResultBean taskRes = (GridClientTaskResultBean)res.getResponse();

                        taskRes.setResult(interceptor.onSend(taskRes.getResult()));
                    }

                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Applies interceptor to a response object.
     * Specially handler {@link Map} and {@link Collection} responses.
     *
     * @param obj Response object.
     * @param interceptor Interceptor to apply.
     * @return Intercepted object.
     */
    private static Object interceptSendObject(Object obj, ConnectorMessageInterceptor interceptor) {
        if (obj instanceof Map) {
            Map<Object, Object> original = (Map<Object, Object>)obj;

            Map<Object, Object> m = new HashMap<>();

            for (Map.Entry e : original.entrySet())
                m.put(interceptor.onSend(e.getKey()), interceptor.onSend(e.getValue()));

            return m;
        }
        else if (obj instanceof Collection) {
            Collection<Object> original = (Collection<Object>)obj;

            Collection<Object> c = new ArrayList<>(original.size());

            for (Object e : original)
                c.add(interceptor.onSend(e));

            return c;
        }
        else
            return interceptor.onSend(obj);
    }

    /**
     * Authenticates remote client.
     *
     * @param req Request to authenticate.
     * @return Authentication subject context.
     * @throws IgniteCheckedException If authentication failed.
     */
    private SecurityContext authenticate(GridRestRequest req) throws IgniteCheckedException {
        // Authenticate client if invalid session.
        AuthenticationContext authCtx = new AuthenticationContext();

        authCtx.subjectType(REMOTE_CLIENT);
        authCtx.subjectId(req.clientId());

        SecurityCredentials cred;

        if (req.credentials() instanceof SecurityCredentials)
            cred = (SecurityCredentials)req.credentials();
        else if (req.credentials() instanceof String) {
            String credStr = (String)req.credentials();

            int idx = credStr.indexOf(':');

            cred = idx >= 0 && idx < credStr.length() ?
                new SecurityCredentials(credStr.substring(0, idx), credStr.substring(idx + 1)) :
                new SecurityCredentials(credStr, null);
        }
        else {
            cred = new SecurityCredentials();

            cred.setUserObject(req.credentials());
        }

        authCtx.address(req.address());

        authCtx.credentials(cred);

        SecurityContext subjCtx = ctx.security().authenticate(authCtx);

        // TODO review
        if (subjCtx == null) {
            if (req.credentials() == null)
                throw new IgniteCheckedException("Failed to authenticate remote client (secure session SPI not set?): " + req);
            else
                throw new IgniteCheckedException("Failed to authenticate remote client (invalid credentials?): " + req);
        }

        return subjCtx;
    }

    /**
     * Update session.
     * @param req REST request.
     * @param sCtx Security context.
     */
    private void updateSession(GridRestRequest req, SecurityContext sCtx) {
//        if (sCtx != null) {
//            sesMap.put(req.clientId(), sCtx);
//
//        }
    }

    /**
     * @param req REST request.
     * @param sCtx Security context.
     * @throws SecurityException If authorization failed.
     */
    private void authorize(GridRestRequest req, SecurityContext sCtx) throws SecurityException {
        SecurityPermission perm = null;
        String name = null;

        switch (req.command()) {
            case CACHE_GET:
            case CACHE_GET_ALL:
                perm = SecurityPermission.CACHE_READ;
                name = ((GridRestCacheRequest)req).cacheName();

                break;

            case CACHE_PUT:
            case CACHE_ADD:
            case CACHE_PUT_ALL:
            case CACHE_REPLACE:
            case CACHE_CAS:
            case CACHE_APPEND:
            case CACHE_PREPEND:
                perm = SecurityPermission.CACHE_PUT;
                name = ((GridRestCacheRequest)req).cacheName();

                break;

            case CACHE_REMOVE:
            case CACHE_REMOVE_ALL:
                perm = SecurityPermission.CACHE_REMOVE;
                name = ((GridRestCacheRequest)req).cacheName();

                break;

            case EXE:
            case RESULT:
                perm = SecurityPermission.TASK_EXECUTE;
                name = ((GridRestTaskRequest)req).taskName();

                break;

            case CACHE_METRICS:
            case TOPOLOGY:
            case NODE:
            case VERSION:
            case NOOP:
            case QUIT:
            case ATOMIC_INCREMENT:
            case ATOMIC_DECREMENT:
                break;

            default:
                throw new AssertionError("Unexpected command: " + req.command());
        }

        if (perm != null)
            ctx.security().authorize(name, perm, sCtx);
    }

    /**
     *
     * @return Whether or not REST is enabled.
     */
    private boolean isRestEnabled() {
        return !ctx.config().isDaemon() && ctx.config().getConnectorConfiguration() != null;
    }

    /**
     * @param hnd Command handler.
     */
    private void addHandler(GridRestCommandHandler hnd) {
        assert !handlers.containsValue(hnd);

        if (log.isDebugEnabled())
            log.debug("Added REST command handler: " + hnd);

        for (GridRestCommand cmd : hnd.supportedCommands()) {
            assert !handlers.containsKey(cmd) : cmd;

            handlers.put(cmd, hnd);
        }
    }

    /**
     * Starts TCP protocol.
     *
     * @throws IgniteCheckedException In case of error.
     */
    private void startTcpProtocol() throws IgniteCheckedException {
        startProtocol(new GridTcpRestProtocol(ctx));
    }

    /**
     * Starts HTTP protocol if it exists on classpath.
     *
     * @throws IgniteCheckedException In case of error.
     */
    private void startHttpProtocol() throws IgniteCheckedException {
        try {
            Class<?> cls = Class.forName(HTTP_PROTO_CLS);

            Constructor<?> ctor = cls.getConstructor(GridKernalContext.class);

            GridRestProtocol proto = (GridRestProtocol)ctor.newInstance(ctx);

            startProtocol(proto);
        }
        catch (ClassNotFoundException ignored) {
            if (log.isDebugEnabled())
                log.debug("Failed to initialize HTTP REST protocol (consider adding ignite-rest-http " +
                    "module to classpath).");
        }
        catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new IgniteCheckedException("Failed to initialize HTTP REST protocol.", e);
        }
    }

    /**
     * @return Client configuration.
     */
    private ConnectorConfiguration config() {
        return ctx.config().getConnectorConfiguration();
    }

    /**
     * @param proto Protocol.
     * @throws IgniteCheckedException If protocol initialization failed.
     */
    private void startProtocol(GridRestProtocol proto) throws IgniteCheckedException {
        assert proto != null;
        assert !protos.contains(proto);

        protos.add(proto);

        proto.start(protoHnd);

        if (log.isDebugEnabled())
            log.debug("Added REST protocol: " + proto);
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> REST processor memory stats [grid=" + ctx.gridName() + ']');
        X.println(">>>   protosSize: " + protos.size());
        X.println(">>>   handlersSize: " + handlers.size());
    }

    /**
     * Session.
     */
    private static class Session {
        private static final Long EXPIRED_FLAG = -0l;

        /** Client id. */
        volatile UUID clientId;

        /** Session token id. */
        volatile UUID sesTokId;

        /** Security context. */
        volatile SecurityContext secCtx;

        final AtomicLong lastTouchTime = new AtomicLong(U.currentTimeMillis());

        /**
         * @return Session token as bytes.
         */
        byte[] sesTok(){
            return U.uuidToBytes(sesTokId);
        }

        /**
         * // TODO javadoc
         */
        boolean checkExpiration() {
            long time0 = lastTouchTime.get();

            if (U.currentTimeMillis() - time0 > SES_EXPERATION_TIME)
                lastTouchTime.compareAndSet(time0, EXPIRED_FLAG);

            return lastTouchTime.get() == EXPIRED_FLAG;
        }

        /**
         * // TODO javadoc
         * @return <code>True</code> if expired.
         */
        boolean checkExpirationAndTryUpdateLastTouchTime() {
            while(true) {
                long time0 = lastTouchTime.get();

                if (time0 == EXPIRED_FLAG)
                    return true;

                boolean success = lastTouchTime.compareAndSet(time0, U.currentTimeMillis());

                if (success)
                    return false;
            }
        }

        /**
         * @return <code>True</code> if session in expired state.
         */
        boolean isExpired() {
            return lastTouchTime.get() == EXPIRED_FLAG;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof Session))
                return false;

            Session ses = (Session)o;

            if (clientId != null ? !clientId.equals(ses.clientId) : ses.clientId != null)
                return false;
            if (sesTokId != null ? !sesTokId.equals(ses.sesTokId) : ses.sesTokId != null)
                return false;

            return true;
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int res = clientId != null ? clientId.hashCode() : 0;
            res = 31 * res + (sesTokId != null ? sesTokId.hashCode() : 0);
            return res;
        }
    }
}
