/*
 * COMSAT
 * Copyright (c) 2015-2016, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.comsat.webactors.netty;

import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.ActorImpl;
import co.paralleluniverse.actors.ActorRef;
import co.paralleluniverse.actors.ActorSpec;
import co.paralleluniverse.common.reflection.AnnotationUtil;
import co.paralleluniverse.common.reflection.ClassLoaderUtil;
import co.paralleluniverse.common.util.Pair;
import co.paralleluniverse.comsat.webactors.WebActor;
import co.paralleluniverse.comsat.webactors.WebMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author circlespainter
 */
public final class AutoWebActorHandler extends WebActorHandler {
    private static final AttributeKey<Context> SESSION_KEY = AttributeKey.newInstance(AutoWebActorHandler.class.getName() + ".session");

    private static final InternalLogger log = InternalLoggerFactory.getInstance(AutoWebActorHandler.class);
    private static final List<Class<?>> actorClasses = new ArrayList<>(4);
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    public AutoWebActorHandler() {
        this(null, null, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName) {
        this(httpResponseEncoderName, null, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, ClassLoader userClassLoader) {
        this(httpResponseEncoderName, userClassLoader, null);
    }

    public AutoWebActorHandler(String httpResponseEncoderName, Map<Class<?>, Object[]> actorParams) {
        this(httpResponseEncoderName, null, actorParams);
    }

    public AutoWebActorHandler(final String httpResponseEncoderName, final ClassLoader userClassLoader, final Map<Class<?>, Object[]> actorParams) {
        super(new AutoContextProvider(userClassLoader, actorParams), httpResponseEncoderName);
    }

    private static Context newActorContext(final FullHttpRequest req, final ClassLoader userClassLoader, final Map<Class<?>, Object[]> actorParams) {
        return new AutoContext(req, actorParams, userClassLoader);
    }

    private static class AutoContextProvider implements WebActorContextProvider {
        private final ClassLoader userClassLoader;
        private final Map<Class<?>, Object[]> actorParams;

        public AutoContextProvider(ClassLoader userClassLoader, Map<Class<?>, Object[]> actorParams) {
            this.userClassLoader = userClassLoader;
            this.actorParams = actorParams;
        }

        @Override
        public final Context get(ChannelHandlerContext ctx, final FullHttpRequest req) {
            final Attribute<Context> s = ctx.attr(SESSION_KEY);
            if (s.get() == null) {
                final String sessionId = getSessionId(req);
                System.err.println("Session ID: " + sessionId);
                System.err.println("Sessions enabled: " + sessionsEnabled());
                if (sessionId != null && sessionsEnabled()) {
                    final Context actorContext = sessions.get(sessionId);
                    if (actorContext != null) {
                        if (actorContext.isValid()) {
                            s.set(actorContext);
                            return actorContext;
                        } else
                            sessions.remove(sessionId); // Evict session
                    }
                }

                final Context actorContext = newActorContext(req, userClassLoader, actorParams);
                s.set(actorContext);
                return actorContext;
            }
            return s.get();
        }

        private String getSessionId(FullHttpRequest req) {
            final String cookiesString = req.headers().get(HttpHeaders.Names.COOKIE);
            if (cookiesString != null) {
                final Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookiesString);
                if (cookies != null) {
                    for (final Cookie c : cookies) {
                        if (c != null && SESSION_COOKIE_KEY.equals(c.name()))
                            return c.value();
                    }
                }
            }
            return null;
        }
    }

    private static class AutoContext extends DefaultContextImpl {
        private final Map<Class<?>, Object[]> actorParams;
        private final ClassLoader userClassLoader;
        private Class<? extends ActorImpl<? extends WebMessage>> actorClass;
        private ActorRef<? extends WebMessage> actorRef;

        public AutoContext(FullHttpRequest req, Map<Class<?>, Object[]> actorParams, ClassLoader userClassLoader) {
            this.actorParams = actorParams;
            this.userClassLoader = userClassLoader;
            final Pair<ActorRef<? extends WebMessage>, Class<? extends ActorImpl<? extends WebMessage>>> p = autoCreateActor(req);
            if (p != null) {
                actorRef = p.getFirst();
                actorClass = p.getSecond();
            }
        }

        @Override
        public final ActorRef<? extends WebMessage> getRef() {
            return actorRef;
        }

        @Override
        public final boolean handlesWithHttp(String uri) {
            return WebActorHandler.handlesWithHttp(uri, actorClass);
        }

        @Override
        public final boolean handlesWithWebSocket(String uri) {
            return WebActorHandler.handlesWithWebSocket(uri, actorClass);
        }

        @SuppressWarnings("unchecked")
        private Pair<ActorRef<? extends WebMessage>, Class<? extends ActorImpl<? extends WebMessage>>> autoCreateActor(FullHttpRequest req) {
            registerActorClasses();
            final String uri = req.getUri();
            for (final Class<?> c : actorClasses) {
                if (WebActorHandler.handlesWithHttp(uri, c) || WebActorHandler.handlesWithWebSocket(uri, c))
                    return new Pair<ActorRef<? extends WebMessage>, Class<? extends ActorImpl<? extends WebMessage>>>(
                        Actor.newActor(
                            new ActorSpec(c, actorParams != null ? actorParams.get(c) : EMPTY_OBJECT_ARRAY)
                        ).spawn(),
                        (Class<? extends ActorImpl<? extends WebMessage>>) c
                    );
            }
            return null;
        }

        private synchronized void registerActorClasses() {
            if (actorClasses.isEmpty()) {
                try {
                    final ClassLoader classLoader = userClassLoader != null ? userClassLoader : this.getClass().getClassLoader();
                    ClassLoaderUtil.accept((URLClassLoader) classLoader, new ClassLoaderUtil.Visitor() {
                        @Override
                        public final void visit(String resource, URL url, ClassLoader cl) {
                            if (!ClassLoaderUtil.isClassFile(resource))
                                return;
                            final String className = ClassLoaderUtil.resourceToClass(resource);
                            try (final InputStream is = cl.getResourceAsStream(resource)) {
                                if (AnnotationUtil.hasClassAnnotation(WebActor.class, is))
                                    registerWebActor(cl.loadClass(className));
                            } catch (final IOException | ClassNotFoundException e) {
                                log.error("Exception while scanning class " + className + " for WebActor annotation", e);
                                throw new RuntimeException(e);
                            }
                        }

                        private void registerWebActor(Class<?> c) {
                            actorClasses.add(c);
                        }
                    });
                } catch (final IOException e) {
                    log.error("IOException while scanning classes for WebActor annotation", e);
                }
            }
        }
    }
}
