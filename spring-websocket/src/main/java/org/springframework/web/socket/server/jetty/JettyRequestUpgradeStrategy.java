/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.socket.server.jetty;

import java.io.IOException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

import org.springframework.context.Lifecycle;
import org.springframework.core.NamedThreadLocal;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.adapter.jetty.JettyWebSocketHandlerAdapter;
import org.springframework.web.socket.adapter.jetty.JettyWebSocketSession;
import org.springframework.web.socket.adapter.jetty.WebSocketToJettyExtensionConfigAdapter;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.RequestUpgradeStrategy;

/**
 * A {@link RequestUpgradeStrategy} for use with Jetty 9.4. Based on Jetty's
 * internal {@code org.eclipse.jetty.websocket.server.WebSocketHandler} class.
 *
 * @author Phillip Webb
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @author Juergen Hoeller
 * @since 4.0
 */
public class JettyRequestUpgradeStrategy implements RequestUpgradeStrategy, ServletContextAware, Lifecycle {

	private static final ThreadLocal<WebSocketHandlerContainer> containerHolder =
			new NamedThreadLocal<>("WebSocketHandlerContainer");

	@Nullable
	private WebSocketPolicy policy;

	@Nullable
	private volatile WebSocketServerFactory factory;

	@Nullable
	private ServletContext servletContext;

	private volatile boolean running;

	@Nullable
	private volatile List<WebSocketExtension> supportedExtensions;


	/**
	 * Default constructor that creates {@link WebSocketServerFactory} through
	 * its default constructor thus using a default {@link WebSocketPolicy}.
	 */
	public JettyRequestUpgradeStrategy() {
		this.policy = WebSocketPolicy.newServerPolicy();
	}

	/**
	 * A constructor accepting a {@link WebSocketPolicy} to be used when
	 * creating the {@link WebSocketServerFactory} instance.
	 * @param policy 要使用的policy
	 * @since 4.3.5
	 */
	public JettyRequestUpgradeStrategy(WebSocketPolicy policy) {
		Assert.notNull(policy, "WebSocketPolicy must not be null");
		this.policy = policy;
	}

	/**
	 * A constructor accepting a {@link WebSocketServerFactory}.
	 * @param factory the pre-configured factory to use
	 */
	public JettyRequestUpgradeStrategy(WebSocketServerFactory factory) {
		Assert.notNull(factory, "WebSocketServerFactory must not be null");
		this.factory = factory;
	}


	@Override
	public void setServletContext(ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	@Override
	public void start() {
		if (!isRunning()) {
			this.running = true;
			try {
				WebSocketServerFactory factory = this.factory;
				if (factory == null) {
					Assert.state(this.servletContext != null, "No ServletContext set");
					factory = new WebSocketServerFactory(this.servletContext, this.policy);
					this.factory = factory;
				}
				factory.setCreator((request, response) -> {
					WebSocketHandlerContainer container = containerHolder.get();
					Assert.state(container != null, "Expected WebSocketHandlerContainer");
					response.setAcceptedSubProtocol(container.getSelectedProtocol());
					response.setExtensions(container.getExtensionConfigs());
					return container.getHandler();
				});
				factory.start();
			}
			catch (Throwable ex) {
				throw new IllegalStateException("Unable to start Jetty WebSocketServerFactory", ex);
			}
		}
	}

	@Override
	public void stop() {
		if (isRunning()) {
			this.running = false;
			WebSocketServerFactory factory = this.factory;
			if (factory != null) {
				try {
					factory.stop();
				}
				catch (Throwable ex) {
					throw new IllegalStateException("Unable to stop Jetty WebSocketServerFactory", ex);
				}
			}
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}


	@Override
	public String[] getSupportedVersions() {
		return new String[] { String.valueOf(13) };
	}

	@Override
	public List<WebSocketExtension> getSupportedExtensions(ServerHttpRequest request) {
		List<WebSocketExtension> extensions = this.supportedExtensions;
		if (extensions == null) {
			extensions = buildWebSocketExtensions();
			this.supportedExtensions = extensions;
		}
		return extensions;
	}

	private List<WebSocketExtension> buildWebSocketExtensions() {
		Set<String> names = getExtensionNames();
		List<WebSocketExtension> result = new ArrayList<>(names.size());
		for (String name : names) {
			result.add(new WebSocketExtension(name));
		}
		return result;
	}

	@SuppressWarnings({"unchecked", "deprecation"})
	private Set<String> getExtensionNames() {
		WebSocketServerFactory factory = this.factory;
		Assert.state(factory != null, "No WebSocketServerFactory available");
		try {
			return factory.getAvailableExtensionNames();
		}
		catch (IncompatibleClassChangeError ex) {
			// Fallback for versions prior to 9.4.21:
			// 9.4.20.v20190813: ExtensionFactory (abstract class -> interface)
			// 9.4.21.v20190926: ExtensionFactory (interface -> abstract class) + deprecated
			Class<?> clazz = org.eclipse.jetty.websocket.api.extensions.ExtensionFactory.class;
			Method method = ClassUtils.getMethod(clazz, "getExtensionNames");
			Set<String> result = (Set<String>) ReflectionUtils.invokeMethod(method, factory.getExtensionFactory());
			return (result != null ? result : Collections.emptySet());
		}
	}

	@Override
	public void upgrade(ServerHttpRequest request, ServerHttpResponse response,
			@Nullable String selectedProtocol, List<WebSocketExtension> selectedExtensions, @Nullable Principal user,
			WebSocketHandler wsHandler, Map<String, Object> attributes) throws HandshakeFailureException {

		Assert.isInstanceOf(ServletServerHttpRequest.class, request, "ServletServerHttpRequest required");
		HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();

		Assert.isInstanceOf(ServletServerHttpResponse.class, response, "ServletServerHttpResponse required");
		HttpServletResponse servletResponse = ((ServletServerHttpResponse) response).getServletResponse();

		WebSocketServerFactory factory = this.factory;
		Assert.state(factory != null, "No WebSocketServerFactory available");
		Assert.isTrue(factory.isUpgradeRequest(servletRequest, servletResponse), "Not a WebSocket handshake");

		JettyWebSocketSession session = new JettyWebSocketSession(attributes, user);
		JettyWebSocketHandlerAdapter handlerAdapter = new JettyWebSocketHandlerAdapter(wsHandler, session);

		WebSocketHandlerContainer container =
				new WebSocketHandlerContainer(handlerAdapter, selectedProtocol, selectedExtensions);

		try {
			containerHolder.set(container);
			factory.acceptWebSocket(servletRequest, servletResponse);
		}
		catch (IOException ex) {
			throw new HandshakeFailureException(
					"Response update failed during upgrade to WebSocket: " + request.getURI(), ex);
		}
		finally {
			containerHolder.remove();
		}
	}


	private static class WebSocketHandlerContainer {

		private final JettyWebSocketHandlerAdapter handler;

		@Nullable
		private final String selectedProtocol;

		private final List<ExtensionConfig> extensionConfigs;

		public WebSocketHandlerContainer(JettyWebSocketHandlerAdapter handler,
				@Nullable String protocol, List<WebSocketExtension> extensions) {

			this.handler = handler;
			this.selectedProtocol = protocol;
			if (CollectionUtils.isEmpty(extensions)) {
				this.extensionConfigs = new ArrayList<>(0);
			}
			else {
				this.extensionConfigs = new ArrayList<>(extensions.size());
				for (WebSocketExtension extension : extensions) {
					this.extensionConfigs.add(new WebSocketToJettyExtensionConfigAdapter(extension));
				}
			}
		}

		public JettyWebSocketHandlerAdapter getHandler() {
			return this.handler;
		}

		@Nullable
		public String getSelectedProtocol() {
			return this.selectedProtocol;
		}

		public List<ExtensionConfig> getExtensionConfigs() {
			return this.extensionConfigs;
		}
	}

}
