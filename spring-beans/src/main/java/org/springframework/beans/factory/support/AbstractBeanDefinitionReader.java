/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.beans.factory.support;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * 实现{@link BeanDefinitionReader}接口的bean定义reader的抽象基类.
 *
 * <p>提供公共属性, 如要处理的bean工厂和用于加载bean类的类加载器.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 11.12.2003
 * @see BeanDefinitionReaderUtils
 */
public abstract class AbstractBeanDefinitionReader implements BeanDefinitionReader, EnvironmentCapable {

	/** 可用于子类的Logger. */
	protected final Log logger = LogFactory.getLog(getClass());

	private final BeanDefinitionRegistry registry;

	@Nullable
	private ResourceLoader resourceLoader;

	@Nullable
	private ClassLoader beanClassLoader;

	private Environment environment;

	private BeanNameGenerator beanNameGenerator = DefaultBeanNameGenerator.INSTANCE;


	/**
	 * 为给定的bean工厂创建一个新的AbstractBeanDefinitionReader.
	 * <p>如果传入的bean工厂不仅实现了BeanDefinitionRegistry接口,
	 * 而且还实现了ResourceLoader接口, 那么它也将被用作默认的ResourceLoader.
	 * 这通常是{@link org.springframework.context.ApplicationContext}实现的情况.
	 * <p>如果给出一个普通的BeanDefinitionRegistry, 默认的ResourceLoader将是
	 * {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver}.
	 * <p>如果 传入的bean工厂也实现了{@link EnvironmentCapable}接口, 那么这个reader将使用它的environment.
	 * 否则, reader将初始化并使用{@link StandardEnvironment}. 所有的ApplicationContext实现都是支持
	 * environment的, 而普通的BeanFactory实现则不是.
	 * @param registry 以BeanDefinitionRegistry的形式将bean定义加载到BeanFactory中
	 * @see #setResourceLoader
	 * @see #setEnvironment
	 */
	protected AbstractBeanDefinitionReader(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		this.registry = registry;

		// 确定要使用的ResourceLoader.
		if (this.registry instanceof ResourceLoader) {
			this.resourceLoader = (ResourceLoader) this.registry;
		}
		else {
			this.resourceLoader = new PathMatchingResourcePatternResolver();
		}

		// 尽可能继承Environment
		if (this.registry instanceof EnvironmentCapable) {
			this.environment = ((EnvironmentCapable) this.registry).getEnvironment();
		}
		else {
			this.environment = new StandardEnvironment();
		}
	}


	public final BeanDefinitionRegistry getBeanFactory() {
		return this.registry;
	}

	@Override
	public final BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	/**
	 * 设置用于resource locations的ResourceLoader.
	 * 如果指定ResourcePatternResolver, bean定义reader将能够把resource patterns
	 * 解析为Resource数组.
	 * <p>默认值是PathMatchingResourcePatternResolver, 也能够通过ResourcePatternResolver
	 * 接口进行resource patter解析.
	 * <p>将其设置为{@code null}表示此bean定义reader不能进行绝对资源加载.
	 * @see org.springframework.core.io.support.ResourcePatternResolver
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	public void setResourceLoader(@Nullable ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	@Nullable
	public ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	/**
	 * 设置用于bean类的ClassLoader.
	 * <p>默认值是{@code null}, 这意味着不要急于加载bean类,
	 * 而是只注册带有雷鸣的bean定义, 相应的类将在稍后(或从不)解析.
	 * @see Thread#getContextClassLoader()
	 */
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
	}

	@Override
	@Nullable
	public ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	/**
	 * 设置读取bean定义时使用的environment.
	 * 通常用于评估配置文件信息, 以确定哪些bean定义应该读取, 哪些应该删除.
	 */
	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public Environment getEnvironment() {
		return this.environment;
	}

	/**
	 * 设置用于匿名bean的BeanNameGenerator(不指定显式的bean名称).
	 * <p>默认值为{@link DefaultBeanNameGenerator}.
	 */
	public void setBeanNameGenerator(@Nullable BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator = (beanNameGenerator != null ? beanNameGenerator : DefaultBeanNameGenerator.INSTANCE);
	}

	@Override
	public BeanNameGenerator getBeanNameGenerator() {
		return this.beanNameGenerator;
	}


	@Override
	public int loadBeanDefinitions(Resource... resources) throws BeanDefinitionStoreException {
		Assert.notNull(resources, "Resource array must not be null");
		int count = 0;
		for (Resource resource : resources) {
			count += loadBeanDefinitions(resource);
		}
		return count;
	}

	@Override
	public int loadBeanDefinitions(String location) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(location, null);
	}

	/**
	 * 从指定的resource location加载bean定义.
	 * <p>这个location也可以是一个location pattern, 前提是这个bean定义reader的ResourceLoader
	 * 是一个ResourcePatternResolver.
	 * @param location resource location, 要用这个bean定义reader的ResourceLoader
	 * (或ResourcePatternResolver)加载
	 * @param actualResources 加使用加载过程中解析的实际Resource对象填充的Set.
	 * 可能是{@code null}, 表示调用者对那些Resource对象不感兴趣.
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 * @see #getResourceLoader()
	 * @see #loadBeanDefinitions(org.springframework.core.io.Resource)
	 * @see #loadBeanDefinitions(org.springframework.core.io.Resource[])
	 */
	public int loadBeanDefinitions(String location, @Nullable Set<Resource> actualResources) throws BeanDefinitionStoreException {
		ResourceLoader resourceLoader = getResourceLoader();
		if (resourceLoader == null) {
			throw new BeanDefinitionStoreException(
					"Cannot load bean definitions from location [" + location + "]: no ResourceLoader available");
		}

		if (resourceLoader instanceof ResourcePatternResolver) {
			// 可用的Resource pattern匹配.
			try {
				Resource[] resources = ((ResourcePatternResolver) resourceLoader).getResources(location);
				int count = loadBeanDefinitions(resources);
				if (actualResources != null) {
					Collections.addAll(actualResources, resources);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Loaded " + count + " bean definitions from location pattern [" + location + "]");
				}
				return count;
			}
			catch (IOException ex) {
				throw new BeanDefinitionStoreException(
						"Could not resolve bean definition resource pattern [" + location + "]", ex);
			}
		}
		else {
			// 只能通过绝对URL加载单个resource.
			Resource resource = resourceLoader.getResource(location);
			int count = loadBeanDefinitions(resource);
			if (actualResources != null) {
				actualResources.add(resource);
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Loaded " + count + " bean definitions from location [" + location + "]");
			}
			return count;
		}
	}

	@Override
	public int loadBeanDefinitions(String... locations) throws BeanDefinitionStoreException {
		Assert.notNull(locations, "Location array must not be null");
		int count = 0;
		for (String location : locations) {
			count += loadBeanDefinitions(location);
		}
		return count;
	}

}
