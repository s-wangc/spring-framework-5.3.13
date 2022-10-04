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

package org.springframework.aop.framework.autoproxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.aop.Advice;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ProxyProcessorSupport;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor}实现, 它用
 * AOP代理包装每个合格的bean, 在调用bean本身之前委托给指定的interceptor.
 *
 * <p>这个类区分了"common" interceptor(为它创建的所有代理共享)和"specific" interceptor(每个bean
 * 实例唯一). 不需要任何共同的interceptor. 如果有, 则使用interceptorNames属性设置它们.
 * 与{@link org.springframework.aop.framework.ProxyFactoryBean}一样, 使用当前工厂中的interceptor
 * 名称而不是bean引用, 以允许正确处理prototype advisor和interceptor: 例如, 支持有状态mixin.
 * 对于{@link #setInterceptorNames "interceptorNames"}项, 支持任何通知类型.
 *
 * <p>如果需要用类似的代理包装大量bean, 即委托给相同的interceptor, 那么这种自动代理特别有用.
 * 您可以向bean工厂注册一个这样的post processor, 而不是为x个目标bean注册x个重复的代理定义,
 * 以达到相同的效果.
 *
 * <p>子类可以应用任何策略来决定一个bean是否需要被代理, 例如按类型、按名称、按定义细节等. 它们还可以返回应该
 * 值应用于特定bean实例的附件interceptor. 一个简单的具体实现是{@link BeanNameAutoProxyCreator}, 它
 * 通过给定的名称标识要代理的bean.
 *
 * <p>可以使用任意数量的{@link TargetSourceCreator}实现来创建自定义目标source: 将prototype对象
 * 池化. 即使没有advice, 只要TargetSourceCreator指定了自定义{@link org.springframework.aop.TargetSource},
 * 自动代理也会发生. 如果没有设置TargetSourceCreators, 或者没有匹配, 默认情况下将使用
 * {@link org.springframework.aop.target.SingletonTargetSource}来包装目标bean实例.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Rob Harrop
 * @since 13.10.2003
 * @see #setInterceptorNames
 * @see #getAdvicesAndAdvisorsForBean
 * @see BeanNameAutoProxyCreator
 * @see DefaultAdvisorAutoProxyCreator
 */
@SuppressWarnings("serial")
public abstract class AbstractAutoProxyCreator extends ProxyProcessorSupport
		implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

	/**
	 * 子类的方便常量: "do not proxy"的返回值.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Nullable
	protected static final Object[] DO_NOT_PROXY = null;

	/**
	 * 子类的方便常量: "没有附加interceptor的代理, 只有普通的"值.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	protected static final Object[] PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS = new Object[0];


	/** 可用于子类的Logger. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** 默认是全局的AdvisorAdapterRegistry. */
	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	/**
	 * 指示是否应该frozen代理. Overridden from super
	 * to prevent the configuration from becoming frozen too early.
	 */
	private boolean freezeProxy = false;

	/** 默认是没有common interceptors. */
	private String[] interceptorNames = new String[0];

	private boolean applyCommonInterceptorsFirst = true;

	@Nullable
	private TargetSourceCreator[] customTargetSourceCreators;

	@Nullable
	private BeanFactory beanFactory;

	private final Set<String> targetSourcedBeans = Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	private final Map<Object, Object> earlyProxyReferences = new ConcurrentHashMap<>(16);

	private final Map<Object, Class<?>> proxyTypes = new ConcurrentHashMap<>(16);

	private final Map<Object, Boolean> advisedBeans = new ConcurrentHashMap<>(256);


	/**
	 * 设置是否应该frozen代理, 以防止在创建代理后向其添加advice.
	 * <p>从超类重写, 以防止在创建代理之前frozen代理配置.
	 */
	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	@Override
	public boolean isFrozen() {
		return this.freezeProxy;
	}

	/**
	 * 指定要使用的{@link AdvisorAdapterRegistry}.
	 * <p>默认是全局{@link AdvisorAdapterRegistry}.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	/**
	 * 将自定义{@code TargetSourceCreators设置为按此顺序应用.
	 * 如果list为空, 或者它们都返回null, 将为每个bean创建一个{@link SingletonTargetSource}.
	 * <p>注意, 即使对于没有找到advice或advisor的目标bean, TargetSourceCreators也会启动.
	 * 如果{@code TargetSourceCreator}为特定bean返回{@link TargetSource}, 则该bean将在
	 * 任何情况下被代理.
	 * <p>{@code TargetSourceCreators}只能在{@link BeanFactory}中使用post processor
	 * 并触发其{@link BeanFactoryAware}回调时使用.
	 * @param targetSourceCreators {@code TargetSourceCreators}的list.
	 * 排序很重要: 将使用从第一个匹配{@code TargetSourceCreator}(即第一个返回非null的)返回的
	 * {@code TargetSource}.
	 */
	public void setCustomTargetSourceCreators(TargetSourceCreator... targetSourceCreators) {
		this.customTargetSourceCreators = targetSourceCreators;
	}

	/**
	 * 设置common interceptors. 这些必须是当前工厂中的bean名称.
	 * 它们可以是Spring支持的任何advice或advisor类型.
	 * <p>如果不设置此属性, 将不会有common interceptor.
	 * 如果我们只需要匹配Advisor之类的"specific" interceptor, 那么这是完全有效的.
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * 设置common interceptors是否应在特定于bean的interceptor之前应用.
	 * 默认值是"true"; 否则, 将首先应用特定于bean的interceptor.
	 */
	public void setApplyCommonInterceptorsFirst(boolean applyCommonInterceptorsFirst) {
		this.applyCommonInterceptorsFirst = applyCommonInterceptorsFirst;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 返回拥有的{@link BeanFactory}.
	 * 可能是{@code null}, 因为这个post-processor不需要属于bean工厂.
	 */
	@Nullable
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	@Override
	@Nullable
	public Class<?> predictBeanType(Class<?> beanClass, String beanName) {
		if (this.proxyTypes.isEmpty()) {
			return null;
		}
		Object cacheKey = getCacheKey(beanClass, beanName);
		return this.proxyTypes.get(cacheKey);
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

	@Override
	public Object getEarlyBeanReference(Object bean, String beanName) {
		Object cacheKey = getCacheKey(bean.getClass(), beanName);
		this.earlyProxyReferences.put(cacheKey, bean);
		return wrapIfNecessary(bean, beanName, cacheKey);
	}

	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
		Object cacheKey = getCacheKey(beanClass, beanName);

		if (!StringUtils.hasLength(beanName) || !this.targetSourcedBeans.contains(beanName)) {
			if (this.advisedBeans.containsKey(cacheKey)) {
				return null;
			}
			if (isInfrastructureClass(beanClass) || shouldSkip(beanClass, beanName)) {
				this.advisedBeans.put(cacheKey, Boolean.FALSE);
				return null;
			}
		}

		// 如果我们有一个自定义的TargetSource, 在这里创建代理.
		// 抑制目标bean不必要的默认实例化:
		// TargetSource将以自定义的方式处理目标实例.
		TargetSource targetSource = getCustomTargetSource(beanClass, beanName);
		if (targetSource != null) {
			if (StringUtils.hasLength(beanName)) {
				this.targetSourcedBeans.add(beanName);
			}
			Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource);
			Object proxy = createProxy(beanClass, beanName, specificInterceptors, targetSource);
			this.proxyTypes.put(cacheKey, proxy.getClass());
			return proxy;
		}

		return null;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		return pvs;  // skip postProcessPropertyValues
	}

	/**
	 * 如果子类将bean标识为要代理的bean, 则使用配置的interceptor创建一个代理.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Override
	public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
		if (bean != null) {
			Object cacheKey = getCacheKey(bean.getClass(), beanName);
			if (this.earlyProxyReferences.remove(cacheKey) != bean) {
				return wrapIfNecessary(bean, beanName, cacheKey);
			}
		}
		return bean;
	}


	/**
	 * 为给定的bean类和bean名构建一个缓存key.
	 * <p>注意: 从4.2.3开始, 这个实现不再返回连接的class/name字符串,
	 * 而是返回最有效的缓存key:
	 * 一个普通的bean名, 如果是{@code FactoryBean}, 则在前面加上
	 * {@link BeanFactory#FACTORY_BEAN_PREFIX};
	 * 或者如果没有指定bean名, 则给定的bean按原样{@code Class}.
	 * @param beanClass the bean class
	 * @param beanName the bean name
	 * @return 给定class和name的缓存key
	 */
	protected Object getCacheKey(Class<?> beanClass, @Nullable String beanName) {
		if (StringUtils.hasLength(beanName)) {
			return (FactoryBean.class.isAssignableFrom(beanClass) ?
					BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
		}
		else {
			return beanClass;
		}
	}

	/**
	 * 如果需要, 包装给定的bean, 例如, 如果它有资格被代理.
	 * @param bean 原始bean实例
	 * @param beanName bean的名称
	 * @param cacheKey 元数据访问的缓存key
	 * @return 包装bean或原始bean实例的代理
	 */
	protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
		if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
			return bean;
		}
		if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
			return bean;
		}
		if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
			this.advisedBeans.put(cacheKey, Boolean.FALSE);
			return bean;
		}

		// 如果我们有advice, 创建代理.
		Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
		if (specificInterceptors != DO_NOT_PROXY) {
			this.advisedBeans.put(cacheKey, Boolean.TRUE);
			Object proxy = createProxy(
					bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
			this.proxyTypes.put(cacheKey, proxy.getClass());
			return proxy;
		}

		this.advisedBeans.put(cacheKey, Boolean.FALSE);
		return bean;
	}

	/**
	 * 返回给定的bean类是否代表一个不应该被代理的infrastructure类.
	 * <p>默认实现将advice、advisor和AopInfrastructureBeans视为infrastructure类.
	 * @param beanClass bean的class
	 * @return bean是否代表一个infrastructure类
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.Advisor
	 * @see org.springframework.aop.framework.AopInfrastructureBean
	 * @see #shouldSkip
	 */
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		boolean retVal = Advice.class.isAssignableFrom(beanClass) ||
				Pointcut.class.isAssignableFrom(beanClass) ||
				Advisor.class.isAssignableFrom(beanClass) ||
				AopInfrastructureBean.class.isAssignableFrom(beanClass);
		if (retVal && logger.isTraceEnabled()) {
			logger.trace("Did not attempt to auto-proxy infrastructure class [" + beanClass.getName() + "]");
		}
		return retVal;
	}

	/**
	 * 如果这个post-processor不考虑对给定的bean进行自动代理, 子类应该重写此方法以返回{@code true}.
	 * <p>有时我们需要能够避免这种情况的发生, 例如, 它是否会导致循环引用, 或者是否需要保留现有的目标
	 * 实例. 这个实现返回{@code false}, 除非bean名称根据{@code AutowireCapableBeanFactory}
	 * 约定指示一个"original instance".
	 * @param beanClass bean的class
	 * @param beanName bean的名称
	 * @return 是否跳过给定的bean
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		return AutoProxyUtils.isOriginalInstance(beanName, beanClass);
	}

	/**
	 * 为bean实例创建一个目标source. 如果设置, 使用任何TargetSourceCreators.
	 * 如果不应使用自定义TargetSource, 则返回{@code null}.
	 * <p>此实现使用"customTargetSourceCreators"属性.
	 * 子类可以重写此方法以使用不同的机制.property.
	 * 如果不应使用自定义TargetSource, 则返回{@code null}.
	 * @param beanClass 要为其创建TargetSource的bean类
	 * @param beanName bean的名称
	 * @return a TargetSource for this bean
	 * @see #setCustomTargetSourceCreators
	 */
	@Nullable
	protected TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		// 我们无法为直接注册的singleton创建奇特的target source.
		if (this.customTargetSourceCreators != null &&
				this.beanFactory != null && this.beanFactory.containsBean(beanName)) {
			for (TargetSourceCreator tsc : this.customTargetSourceCreators) {
				TargetSource ts = tsc.getTargetSource(beanClass, beanName);
				if (ts != null) {
					// 找到匹配的TargetSource.
					if (logger.isTraceEnabled()) {
						logger.trace("TargetSourceCreator [" + tsc +
								"] found custom TargetSource for bean with name '" + beanName + "'");
					}
					return ts;
				}
			}
		}

		// 找不到自定义TargetSource.
		return null;
	}

	/**
	 * 为给定bean创建AOP代理.
	 * @param beanClass bean的class
	 * @param beanName bean的名称
	 * @param specificInterceptors 特定于此bean的interceptor set(可以为空, 但不能为null)
	 * @param targetSource 代理的TargetSource, 已预先配置问access bean
	 * @return bean的AOP代理
	 * @see #buildAdvisors
	 */
	protected Object createProxy(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource) {

		if (this.beanFactory instanceof ConfigurableListableBeanFactory) {
			AutoProxyUtils.exposeTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName, beanClass);
		}

		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.copyFrom(this);

		if (proxyFactory.isProxyTargetClass()) {
			// 显式处理JDK代理target(用于introduction advice方案)
			if (Proxy.isProxyClass(beanClass)) {
				// 必须考虑到introduction; 不能仅将接口设置为代理的接口.
				for (Class<?> ifc : beanClass.getInterfaces()) {
					proxyFactory.addInterface(ifc);
				}
			}
		}
		else {
			// 没有强制执行proxyTargetClass标志, 让我们应用默认检查...
			if (shouldProxyTargetClass(beanClass, beanName)) {
				proxyFactory.setProxyTargetClass(true);
			}
			else {
				evaluateProxyInterfaces(beanClass, proxyFactory);
			}
		}

		Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
		proxyFactory.addAdvisors(advisors);
		proxyFactory.setTargetSource(targetSource);
		customizeProxyFactory(proxyFactory);

		proxyFactory.setFrozen(this.freezeProxy);
		if (advisorsPreFiltered()) {
			proxyFactory.setPreFiltered(true);
		}

		// 如果未在重写类加载器中本地加载bean类, 请使用原始ClassLoader
		ClassLoader classLoader = getProxyClassLoader();
		if (classLoader instanceof SmartClassLoader && classLoader != beanClass.getClassLoader()) {
			classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
		}
		return proxyFactory.getProxy(classLoader);
	}

	/**
	 * 确定给定bean是否应使用其目标类而不是其接口进行代理.
	 * <p>检查相应bean定义的{@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}.
	 * @param beanClass bean的class
	 * @param beanName bean的名称
	 * @return 给定bean是否应使用其target类进行代理
	 * @see AutoProxyUtils#shouldProxyTargetClass
	 */
	protected boolean shouldProxyTargetClass(Class<?> beanClass, @Nullable String beanName) {
		return (this.beanFactory instanceof ConfigurableListableBeanFactory &&
				AutoProxyUtils.shouldProxyTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName));
	}

	/**
	 * 返回子类返回的Advisor是否已经过预筛选, 以匹配bean的target类, 从而允许在为AOP调用构建
	 * Advisor链时跳过ClassFilter检查.
	 * <p>默认值是{@code false}. 如果子类总是返回预先筛选的Advisor, 则可以覆盖此设置.
	 * @return Advisor是否经过预先筛选
	 * @see #getAdvicesAndAdvisorsForBean
	 * @see org.springframework.aop.framework.Advised#setPreFiltered
	 */
	protected boolean advisorsPreFiltered() {
		return false;
	}

	/**
	 * 确定给定bean的advisor, 包括特定的interceptor和通用的interceptor, 所有这些都适应Advisor接口.
	 * @param beanName bean的名称
	 * @param specificInterceptors 特定于此bean的interceptor set(可以为空, 但不为null)
	 * @return 给定bean的advisor list
	 */
	protected Advisor[] buildAdvisors(@Nullable String beanName, @Nullable Object[] specificInterceptors) {
		// 正确处理prototype...
		Advisor[] commonInterceptors = resolveInterceptorNames();

		List<Object> allInterceptors = new ArrayList<>();
		if (specificInterceptors != null) {
			if (specificInterceptors.length > 0) {
				// specificInterceptors可以等于PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
				allInterceptors.addAll(Arrays.asList(specificInterceptors));
			}
			if (commonInterceptors.length > 0) {
				if (this.applyCommonInterceptorsFirst) {
					allInterceptors.addAll(0, Arrays.asList(commonInterceptors));
				}
				else {
					allInterceptors.addAll(Arrays.asList(commonInterceptors));
				}
			}
		}
		if (logger.isTraceEnabled()) {
			int nrOfCommonInterceptors = commonInterceptors.length;
			int nrOfSpecificInterceptors = (specificInterceptors != null ? specificInterceptors.length : 0);
			logger.trace("Creating implicit proxy for bean '" + beanName + "' with " + nrOfCommonInterceptors +
					" common interceptors and " + nrOfSpecificInterceptors + " specific interceptors");
		}

		Advisor[] advisors = new Advisor[allInterceptors.size()];
		for (int i = 0; i < allInterceptors.size(); i++) {
			advisors[i] = this.advisorAdapterRegistry.wrap(allInterceptors.get(i));
		}
		return advisors;
	}

	/**
	 * 将指定的interceptor名称解析为Advisor对象.
	 * @see #setInterceptorNames
	 */
	private Advisor[] resolveInterceptorNames() {
		BeanFactory bf = this.beanFactory;
		ConfigurableBeanFactory cbf = (bf instanceof ConfigurableBeanFactory ? (ConfigurableBeanFactory) bf : null);
		List<Advisor> advisors = new ArrayList<>();
		for (String beanName : this.interceptorNames) {
			if (cbf == null || !cbf.isCurrentlyInCreation(beanName)) {
				Assert.state(bf != null, "BeanFactory required for resolving interceptor names");
				Object next = bf.getBean(beanName);
				advisors.add(this.advisorAdapterRegistry.wrap(next));
			}
		}
		return advisors.toArray(new Advisor[0]);
	}

	/**
	 * 子类可以选择实现这一点: 例如, 更改公开的接口.
	 * <p>默认实现为空.
	 * @param proxyFactory 一个已经配置了TargetSource
	 * 和接口的ProxyFactory, 将在该方法返回后立即用于创建代理
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}


	/**
	 * 返回是否要代理给定的bean, 要应用哪些附加advice(例如AOP Alliance interceptors)
	 * 和advisor.
	 * @param beanClass the class of the bean to advise
	 * @param beanName bean的名称
	 * @param customTargetSource the TargetSource returned by the
	 * {@link #getCustomTargetSource} method: may be ignored.
	 * Will be {@code null} if no custom target source is in use.
	 * @return an array of additional interceptors for the particular bean;
	 * or an empty array if no additional interceptors but just the common ones;
	 * or {@code null} if no proxy at all, not even with the common interceptors.
	 * See constants DO_NOT_PROXY and PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS.
	 * @throws BeansException 如果出现错误
	 * @see #DO_NOT_PROXY
	 * @see #PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
	 */
	@Nullable
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName,
			@Nullable TargetSource customTargetSource) throws BeansException;

}
