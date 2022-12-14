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
 * {@link org.springframework.beans.factory.config.BeanPostProcessor}??????, ??????
 * AOP???????????????????????????bean, ?????????bean??????????????????????????????interceptor.
 *
 * <p>??????????????????"common" interceptor(?????????????????????????????????)???"specific" interceptor(??????bean
 * ????????????). ????????????????????????interceptor. ?????????, ?????????interceptorNames??????????????????.
 * ???{@link org.springframework.aop.framework.ProxyFactoryBean}??????, ????????????????????????interceptor
 * ???????????????bean??????, ?????????????????????prototype advisor???interceptor: ??????, ???????????????mixin.
 * ??????{@link #setInterceptorNames "interceptorNames"}???, ????????????????????????.
 *
 * <p>??????????????????????????????????????????bean, ?????????????????????interceptor, ????????????????????????????????????.
 * ????????????bean???????????????????????????post processor, ????????????x?????????bean??????x????????????????????????,
 * ????????????????????????.
 *
 * <p>?????????????????????????????????????????????bean?????????????????????, ????????????????????????????????????????????????. ???????????????????????????
 * ??????????????????bean???????????????interceptor. ??????????????????????????????{@link BeanNameAutoProxyCreator}, ???
 * ???????????????????????????????????????bean.
 *
 * <p>???????????????????????????{@link TargetSourceCreator}??????????????????????????????source: ???prototype??????
 * ??????. ????????????advice, ??????TargetSourceCreator??????????????????{@link org.springframework.aop.TargetSource},
 * ????????????????????????. ??????????????????TargetSourceCreators, ??????????????????, ????????????????????????
 * {@link org.springframework.aop.target.SingletonTargetSource}???????????????bean??????.
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
	 * ?????????????????????: "do not proxy"????????????.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Nullable
	protected static final Object[] DO_NOT_PROXY = null;

	/**
	 * ?????????????????????: "????????????interceptor?????????, ???????????????"???.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	protected static final Object[] PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS = new Object[0];


	/** ??????????????????Logger. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** ??????????????????AdvisorAdapterRegistry. */
	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	/**
	 * ??????????????????frozen??????. Overridden from super
	 * to prevent the configuration from becoming frozen too early.
	 */
	private boolean freezeProxy = false;

	/** ???????????????common interceptors. */
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
	 * ??????????????????frozen??????, ???????????????????????????????????????advice.
	 * <p>???????????????, ??????????????????????????????frozen????????????.
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
	 * ??????????????????{@link AdvisorAdapterRegistry}.
	 * <p>???????????????{@link AdvisorAdapterRegistry}.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	/**
	 * ????????????{@code TargetSourceCreators???????????????????????????.
	 * ??????list??????, ?????????????????????null, ????????????bean????????????{@link SingletonTargetSource}.
	 * <p>??????, ????????????????????????advice???advisor?????????bean, TargetSourceCreators????????????.
	 * ??????{@code TargetSourceCreator}?????????bean??????{@link TargetSource}, ??????bean??????
	 * ????????????????????????.
	 * <p>{@code TargetSourceCreators}?????????{@link BeanFactory}?????????post processor
	 * ????????????{@link BeanFactoryAware}???????????????.
	 * @param targetSourceCreators {@code TargetSourceCreators}???list.
	 * ???????????????: ???????????????????????????{@code TargetSourceCreator}(?????????????????????null???)?????????
	 * {@code TargetSource}.
	 */
	public void setCustomTargetSourceCreators(TargetSourceCreator... targetSourceCreators) {
		this.customTargetSourceCreators = targetSourceCreators;
	}

	/**
	 * ??????common interceptors. ?????????????????????????????????bean??????.
	 * ???????????????Spring???????????????advice???advisor??????.
	 * <p>????????????????????????, ????????????common interceptor.
	 * ???????????????????????????Advisor?????????"specific" interceptor, ???????????????????????????.
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * ??????common interceptors?????????????????????bean???interceptor????????????.
	 * ????????????"true"; ??????, ????????????????????????bean???interceptor.
	 */
	public void setApplyCommonInterceptorsFirst(boolean applyCommonInterceptorsFirst) {
		this.applyCommonInterceptorsFirst = applyCommonInterceptorsFirst;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * ???????????????{@link BeanFactory}.
	 * ?????????{@code null}, ????????????post-processor???????????????bean??????.
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

		// ?????????????????????????????????TargetSource, ?????????????????????.
		// ????????????bean???????????????????????????:
		// TargetSource??????????????????????????????????????????.
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
	 * ???????????????bean?????????????????????bean, ??????????????????interceptor??????????????????.
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
	 * ????????????bean??????bean?????????????????????key.
	 * <p>??????: ???4.2.3??????, ?????????????????????????????????class/name?????????,
	 * ??????????????????????????????key:
	 * ???????????????bean???, ?????????{@code FactoryBean}, ??????????????????
	 * {@link BeanFactory#FACTORY_BEAN_PREFIX};
	 * ????????????????????????bean???, ????????????bean?????????{@code Class}.
	 * @param beanClass the bean class
	 * @param beanName the bean name
	 * @return ??????class???name?????????key
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
	 * ????????????, ???????????????bean, ??????, ???????????????????????????.
	 * @param bean ??????bean??????
	 * @param beanName bean?????????
	 * @param cacheKey ????????????????????????key
	 * @return ??????bean?????????bean???????????????
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

		// ???????????????advice, ????????????.
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
	 * ???????????????bean??????????????????????????????????????????infrastructure???.
	 * <p>???????????????advice???advisor???AopInfrastructureBeans??????infrastructure???.
	 * @param beanClass bean???class
	 * @return bean??????????????????infrastructure???
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
	 * ????????????post-processor?????????????????????bean??????????????????, ????????????????????????????????????{@code true}.
	 * <p>???????????????????????????????????????????????????, ??????, ??????????????????????????????, ???????????????????????????????????????
	 * ??????. ??????????????????{@code false}, ??????bean????????????{@code AutowireCapableBeanFactory}
	 * ??????????????????"original instance".
	 * @param beanClass bean???class
	 * @param beanName bean?????????
	 * @return ?????????????????????bean
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		return AutoProxyUtils.isOriginalInstance(beanName, beanClass);
	}

	/**
	 * ???bean????????????????????????source. ????????????, ????????????TargetSourceCreators.
	 * ???????????????????????????TargetSource, ?????????{@code null}.
	 * <p>???????????????"customTargetSourceCreators"??????.
	 * ???????????????????????????????????????????????????.property.
	 * ???????????????????????????TargetSource, ?????????{@code null}.
	 * @param beanClass ???????????????TargetSource???bean???
	 * @param beanName bean?????????
	 * @return a TargetSource for this bean
	 * @see #setCustomTargetSourceCreators
	 */
	@Nullable
	protected TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		// ??????????????????????????????singleton???????????????target source.
		if (this.customTargetSourceCreators != null &&
				this.beanFactory != null && this.beanFactory.containsBean(beanName)) {
			for (TargetSourceCreator tsc : this.customTargetSourceCreators) {
				TargetSource ts = tsc.getTargetSource(beanClass, beanName);
				if (ts != null) {
					// ???????????????TargetSource.
					if (logger.isTraceEnabled()) {
						logger.trace("TargetSourceCreator [" + tsc +
								"] found custom TargetSource for bean with name '" + beanName + "'");
					}
					return ts;
				}
			}
		}

		// ??????????????????TargetSource.
		return null;
	}

	/**
	 * ?????????bean??????AOP??????.
	 * @param beanClass bean???class
	 * @param beanName bean?????????
	 * @param specificInterceptors ????????????bean???interceptor set(????????????, ????????????null)
	 * @param targetSource ?????????TargetSource, ??????????????????access bean
	 * @return bean???AOP??????
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
			// ????????????JDK??????target(??????introduction advice??????)
			if (Proxy.isProxyClass(beanClass)) {
				// ???????????????introduction; ??????????????????????????????????????????.
				for (Class<?> ifc : beanClass.getInterfaces()) {
					proxyFactory.addInterface(ifc);
				}
			}
		}
		else {
			// ??????????????????proxyTargetClass??????, ???????????????????????????...
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

		// ?????????????????????????????????????????????bean???, ???????????????ClassLoader
		ClassLoader classLoader = getProxyClassLoader();
		if (classLoader instanceof SmartClassLoader && classLoader != beanClass.getClassLoader()) {
			classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
		}
		return proxyFactory.getProxy(classLoader);
	}

	/**
	 * ????????????bean?????????????????????????????????????????????????????????.
	 * <p>????????????bean?????????{@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}.
	 * @param beanClass bean???class
	 * @param beanName bean?????????
	 * @return ??????bean??????????????????target???????????????
	 * @see AutoProxyUtils#shouldProxyTargetClass
	 */
	protected boolean shouldProxyTargetClass(Class<?> beanClass, @Nullable String beanName) {
		return (this.beanFactory instanceof ConfigurableListableBeanFactory &&
				AutoProxyUtils.shouldProxyTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName));
	}

	/**
	 * ?????????????????????Advisor????????????????????????, ?????????bean???target???, ??????????????????AOP????????????
	 * Advisor????????????ClassFilter??????.
	 * <p>????????????{@code false}. ???????????????????????????????????????Advisor, ????????????????????????.
	 * @return Advisor????????????????????????
	 * @see #getAdvicesAndAdvisorsForBean
	 * @see org.springframework.aop.framework.Advised#setPreFiltered
	 */
	protected boolean advisorsPreFiltered() {
		return false;
	}

	/**
	 * ????????????bean???advisor, ???????????????interceptor????????????interceptor, ?????????????????????Advisor??????.
	 * @param beanName bean?????????
	 * @param specificInterceptors ????????????bean???interceptor set(????????????, ?????????null)
	 * @return ??????bean???advisor list
	 */
	protected Advisor[] buildAdvisors(@Nullable String beanName, @Nullable Object[] specificInterceptors) {
		// ????????????prototype...
		Advisor[] commonInterceptors = resolveInterceptorNames();

		List<Object> allInterceptors = new ArrayList<>();
		if (specificInterceptors != null) {
			if (specificInterceptors.length > 0) {
				// specificInterceptors????????????PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
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
	 * ????????????interceptor???????????????Advisor??????.
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
	 * ?????????????????????????????????: ??????, ?????????????????????.
	 * <p>??????????????????.
	 * @param proxyFactory ?????????????????????TargetSource
	 * ????????????ProxyFactory, ????????????????????????????????????????????????
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}


	/**
	 * ??????????????????????????????bean, ?????????????????????advice(??????AOP Alliance interceptors)
	 * ???advisor.
	 * @param beanClass the class of the bean to advise
	 * @param beanName bean?????????
	 * @param customTargetSource the TargetSource returned by the
	 * {@link #getCustomTargetSource} method: may be ignored.
	 * Will be {@code null} if no custom target source is in use.
	 * @return an array of additional interceptors for the particular bean;
	 * or an empty array if no additional interceptors but just the common ones;
	 * or {@code null} if no proxy at all, not even with the common interceptors.
	 * See constants DO_NOT_PROXY and PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS.
	 * @throws BeansException ??????????????????
	 * @see #DO_NOT_PROXY
	 * @see #PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
	 */
	@Nullable
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName,
			@Nullable TargetSource customTargetSource) throws BeansException;

}
