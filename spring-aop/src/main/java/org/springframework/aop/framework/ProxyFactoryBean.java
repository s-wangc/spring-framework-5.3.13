/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.aop.framework;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.Interceptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.UnknownAdviceTypeException;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * {@link org.springframework.beans.factory.FactoryBean}实现, 基于Spring
 * {@link org.springframework.beans.factory.BeanFactory}中的bean构建AOP代理.
 *
 * <p>{@link org.aopalliance.intercept.MethodInterceptor MethodInterceptors}和
 * {@link org.springframework.aop.Advisor Advisors}由当前bean工厂中的bean名称list
 * 标识, 通过"interceptorNames"属性指定. list中的最后一项可以是target bean或
 * {@link org.springframework.aop.TargetSource}的名称; 但是, 通常最好使用
 * "targetName"/"target"/"targetSource"属性.
 *
 * <p>可以在工厂级别添加全局interceptor和advisor. 在interceptor list中扩展指定的条目, 其中
 * "xxx*"条目包含在list中, 将给定的前缀与bean名称匹配(例如, "global*"将匹配"globalBean1"和
 * "globalBean2", "*"匹配所有定义的interceptors). 如果实现了{@link org.springframework.core.Ordered}
 * 接口, 则根据其返回的order值应用匹配的interceptor.
 *
 * <p>当提供代理接口时创建JDK代理, 如果没有提供代理接口则为实际目标类创建CGLIB代理. 请注意, 后者
 * 仅在目标类没有final方法的情况下才会工作, 因为动态子类将在运行时创建.
 *
 * <p>可操作它. 这将不适用于现有的prototype引用, 它们是独立的. 然而, 它将适用于随后从工厂
 * 获得的prototype. 对拦截的更改将立即作用域singleton(包括现有的引用). 但是, 要更改接口或目标,
 * 就必须从工厂获取一个实例. 这意味着从工厂获得的singleton实例没有相同的对象标识. 然而, 它们具有
 * 相同的interceptor和target, 更改任何引用都将更改所有对象.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #setInterceptorNames
 * @see #setProxyInterfaces
 * @see org.aopalliance.intercept.MethodInterceptor
 * @see org.springframework.aop.Advisor
 * @see Advised
 */
@SuppressWarnings("serial")
public class ProxyFactoryBean extends ProxyCreatorSupport
		implements FactoryBean<Object>, BeanClassLoaderAware, BeanFactoryAware {

	/**
	 * interceptor list中某个值的后缀表示展开全局变量.
	 */
	public static final String GLOBAL_SUFFIX = "*";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private String[] interceptorNames;

	@Nullable
	private String targetName;

	private boolean autodetectInterfaces = true;

	private boolean singleton = true;

	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	private boolean freezeProxy = false;

	@Nullable
	private transient ClassLoader proxyClassLoader = ClassUtils.getDefaultClassLoader();

	private transient boolean classLoaderConfigured = false;

	@Nullable
	private transient BeanFactory beanFactory;

	/** advisor链是否已经初始化. */
	private boolean advisorChainInitialized = false;

	/** 如果这是一个singleton, 缓存的singleton代理实例. */
	@Nullable
	private Object singletonInstance;


	/**
	 * 设置要代理的接口的名称. 如果没有给出接口, 将为实际类创建一个CGLIB.
	 * <p>这本质上等同于"setInterfaces"方法, 但镜像了TransactionProxyFactoryBean
	 * 的"setProxyInterfaces".
	 * @see #setInterfaces
	 * @see AbstractSingletonProxyFactoryBean#setProxyInterfaces
	 */
	public void setProxyInterfaces(Class<?>[] proxyInterfaces) throws ClassNotFoundException {
		setInterfaces(proxyInterfaces);
	}

	/**
	 * 设置Advice/Advisor bean名称的list. 必须始终将此设置为在bean工厂中使用此工厂bean.
	 * <p>引用的bean应该是Interceptor、Advisor或Advice类型. list中的最后一项可以是工厂
	 * 中任何bean的名称. 如果它既不是Advice也不是Advisor, 则会添加一个新的SingletonTargetSource
	 * 来包装它. 如果设置了"target"或"targetSource"或"targetName"属性, 则不能使用这样的
	 * target bean, 在这种情况下, "interceptorNames"数组必须仅包含Advice/Advisor bean名称.
	 * <p><b>注意: 不推荐在"interceptorNames" list中指定target bean作为最终名称, 在未来的
	 * Spring版本中叫删除该名称.</b>
	 * 请使用{@link #setTargetName "targetName"}属性.
	 * @see org.aopalliance.intercept.MethodInterceptor
	 * @see org.springframework.aop.Advisor
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.target.SingletonTargetSource
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * 设置target bean的名称. 这是在"interceptorNames"数组末尾指定target名称的替代方法.
	 * <p>您还可以分别通过"target"/"targetSource"属性直接指定target对象或TargetSource对象.
	 * @see #setInterceptorNames(String[])
	 * @see #setTarget(Object)
	 * @see #setTargetSource(org.springframework.aop.TargetSource)
	 */
	public void setTargetName(String targetName) {
		this.targetName = targetName;
	}

	/**
	 * 设置是否在未指定代理接口的情况下自动检测代理接口.
	 * <p>默认值是"true". 如果未指定接口, 请关闭此标志以创建完整target类的CGLIB代理.
	 * @see #set
	 */
	public void setAutodetectInterfaces(boolean autodetectInterfaces) {
		this.autodetectInterfaces = autodetectInterfaces;
	}

	/**
	 * 设置singleton属性的值. 控制此工厂是否应该始终返回相同的代理实例(这意味着相同的target),
	 * 或者是否应返回新的prototype实例, 这意味着如果target和interceptor是从prototype bean
	 * 定义获得的, 则它们也可能是新实例. 这允许对对象图中的independence/uniqueness进行精细控制.
	 */
	public void setSingleton(boolean singleton) {
		this.singleton = singleton;
	}

	/**
	 * 指定要使用的AdvisorAdapterRegistry.
	 * 默认是全局AdvisorAdapterRegistry.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	/**
	 * 设置ClassLoader以生成代理类.
	 * <p>默认是bean DClassLoader, 即由包含的BeanFactory用于加载所有bean类的
	 * ClassLoader. 对于特定的代理, 可以在这里重写它.
	 */
	public void setProxyClassLoader(@Nullable ClassLoader classLoader) {
		this.proxyClassLoader = classLoader;
		this.classLoaderConfigured = (classLoader != null);
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		if (!this.classLoaderConfigured) {
			this.proxyClassLoader = classLoader;
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		checkInterceptorNames();
	}


	/**
	 * 返回一个代理. 当客户端从这个factory bean获得bean时调用. 创建要由这个factory
	 * 返回的AOP代理的实例. 实例将被缓存为singleton, 并在每次调用{@code getObject()}
	 * 时为代理创建实例.
	 * @return 反映factory当前状态的新AOP代理
	 */
	@Override
	@Nullable
	public Object getObject() throws BeansException {
		initializeAdvisorChain();
		if (isSingleton()) {
			return getSingletonInstance();
		}
		else {
			if (this.targetName == null) {
				logger.info("Using non-singleton proxies with singleton targets is often undesirable. " +
						"Enable prototype proxies by setting the 'targetName' property.");
			}
			return newPrototypeInstance();
		}
	}

	/**
	 * 返回代理的类型. 将检查已经创建的singleton实例, 否则退回到代理接口
	 * (在只有一个代理的情况下)、target bean类型或TargetSource的target类.
	 * @see org.springframework.aop.TargetSource#getTargetClass
	 */
	@Override
	public Class<?> getObjectType() {
		synchronized (this) {
			if (this.singletonInstance != null) {
				return this.singletonInstance.getClass();
			}
		}
		Class<?>[] ifcs = getProxiedInterfaces();
		if (ifcs.length == 1) {
			return ifcs[0];
		}
		else if (ifcs.length > 1) {
			return createCompositeInterface(ifcs);
		}
		else if (this.targetName != null && this.beanFactory != null) {
			return this.beanFactory.getType(this.targetName);
		}
		else {
			return getTargetClass();
		}
	}

	@Override
	public boolean isSingleton() {
		return this.singleton;
	}


	/**
	 * 为给定的接口创建复合接口类, 在一个类中实现给定的接口.
	 * <p>默认实现为给定的接口构建一个JDK代理类.
	 * @param interfaces 要合并的接口
	 * @return 合并的接口作为Class
	 * @see java.lang.reflect.Proxy#getProxyClass
	 */
	protected Class<?> createCompositeInterface(Class<?>[] interfaces) {
		return ClassUtils.createCompositeInterface(interfaces, this.proxyClassLoader);
	}

	/**
	 * 返回该类代理对象的singleton实例, 如果尚未创建, 则延迟创建.
	 * @return 共享singleton代理
	 */
	private synchronized Object getSingletonInstance() {
		if (this.singletonInstance == null) {
			this.targetSource = freshTargetSource();
			if (this.autodetectInterfaces && getProxiedInterfaces().length == 0 && !isProxyTargetClass()) {
				// 依赖AOP infrastructure告诉我们要代理哪些接口.
				Class<?> targetClass = getTargetClass();
				if (targetClass == null) {
					throw new FactoryBeanNotInitializedException("Cannot determine target class for proxy");
				}
				setInterfaces(ClassUtils.getAllInterfacesForClass(targetClass, this.proxyClassLoader));
			}
			// 初始化共享的singleton实例.
			super.setFrozen(this.freezeProxy);
			this.singletonInstance = getProxy(createAopProxy());
		}
		return this.singletonInstance;
	}

	/**
	 * 为这个类创建的代理对象创建一个新的prototype实例, 由独立的AdvisedSupport配置支持.
	 * @return 一个完全独立的代理, 我们可以单独操纵他的advice
	 */
	private synchronized Object newPrototypeInstance() {
		// 在prototype的情况下, 我们需要给代理一个配置的独立实例.
		// 在这种情况下, 没有代理拥有该对象配置的实例, 而是拥有独立的副本.
		ProxyCreatorSupport copy = new ProxyCreatorSupport(getAopProxyFactory());

		// 拷贝需要一个新的advisor链和一个新的TargetSource.
		TargetSource targetSource = freshTargetSource();
		copy.copyConfigurationFrom(this, targetSource, freshAdvisorChain());
		if (this.autodetectInterfaces && getProxiedInterfaces().length == 0 && !isProxyTargetClass()) {
			// 依赖AOP infrastructure告诉我们要代理哪些接口.
			Class<?> targetClass = targetSource.getTargetClass();
			if (targetClass != null) {
				copy.setInterfaces(ClassUtils.getAllInterfacesForClass(targetClass, this.proxyClassLoader));
			}
		}
		copy.setFrozen(this.freezeProxy);

		return getProxy(copy.createAopProxy());
	}

	/**
	 * 返回要公开的代理对象.
	 * <p>默认实现使用工厂的bean class loader的{@code getProxy}调用.
	 * 可重写以指定自定义class loader.
	 * @param aopProxy 准备好的AopProxy实例, 以便从中获取代理
	 * @return 要公开的代理对象
	 * @see AopProxy#getProxy(ClassLoader)
	 */
	protected Object getProxy(AopProxy aopProxy) {
		return aopProxy.getProxy(this.proxyClassLoader);
	}

	/**
	 * 检查interceptorNames list是否包含target name作为final element.
	 * 如果找到, 则从list中删除final name并将其设置为targetName.
	 */
	private void checkInterceptorNames() {
		if (!ObjectUtils.isEmpty(this.interceptorNames)) {
			String finalName = this.interceptorNames[this.interceptorNames.length - 1];
			if (this.targetName == null && this.targetSource == EMPTY_TARGET_SOURCE) {
				// 链中的最后一个名称可能是Advisor/Advice或target/TargetSource.
				// 不幸的是, 我们不知道; 我们必须看bean的类型.
				if (!finalName.endsWith(GLOBAL_SUFFIX) && !isNamedBeanAnAdvisorOrAdvice(finalName)) {
					// target不是interceptor.
					this.targetName = finalName;
					if (logger.isDebugEnabled()) {
						logger.debug("Bean with name '" + finalName + "' concluding interceptor chain " +
								"is not an advisor class: treating it as a target or TargetSource");
					}
					this.interceptorNames = Arrays.copyOf(this.interceptorNames, this.interceptorNames.length - 1);
				}
			}
		}
	}

	/**
	 * 查看bean工厂元数据, 确定这个bean名称(它是interceptorNames list的结尾)
	 * 是Advisor还是Advice, 或者可能是一个target.
	 * @param beanName 要检查的bean名称
	 * @return 如果是Advisor或Advice则为{@code true}
	 */
	private boolean isNamedBeanAnAdvisorOrAdvice(String beanName) {
		Assert.state(this.beanFactory != null, "No BeanFactory set");
		Class<?> namedBeanClass = this.beanFactory.getType(beanName);
		if (namedBeanClass != null) {
			return (Advisor.class.isAssignableFrom(namedBeanClass) || Advice.class.isAssignableFrom(namedBeanClass));
		}
		// 如果我们看不出来, 就把它当成target bean.
		if (logger.isDebugEnabled()) {
			logger.debug("Could not determine type of bean with name '" + beanName +
					"' - assuming it is neither an Advisor nor an Advice");
		}
		return false;
	}

	/**
	 * 创建advisor(interceptor)链. 每当添加一个新的prototype实例时,
	 * 来自BeanFactory的advisor将被刷新. 通过工厂API以编程方式添加的
	 * interceptor不受此类更改的影响.
	 */
	private synchronized void initializeAdvisorChain() throws AopConfigException, BeansException {
		if (this.advisorChainInitialized) {
			return;
		}

		if (!ObjectUtils.isEmpty(this.interceptorNames)) {
			if (this.beanFactory == null) {
				throw new IllegalStateException("No BeanFactory available anymore (probably due to serialization) " +
						"- cannot resolve interceptor names " + Arrays.asList(this.interceptorNames));
			}

			// 全局变量不能是最后一个, 除非我们使用属性指定了targetSource...
			if (this.interceptorNames[this.interceptorNames.length - 1].endsWith(GLOBAL_SUFFIX) &&
					this.targetName == null && this.targetSource == EMPTY_TARGET_SOURCE) {
				throw new AopConfigException("Target required after globals");
			}

			// 从bean名称具体化interceptor链.
			for (String name : this.interceptorNames) {
				if (name.endsWith(GLOBAL_SUFFIX)) {
					if (!(this.beanFactory instanceof ListableBeanFactory)) {
						throw new AopConfigException(
								"Can only use global advisors or interceptors with a ListableBeanFactory");
					}
					addGlobalAdvisors((ListableBeanFactory) this.beanFactory,
							name.substring(0, name.length() - GLOBAL_SUFFIX.length()));
				}

				else {
					// 如果我们到达这里, 我们需要添加一个命名interceptor.
					// 我们必须检查它是singleton还是prototype.
					Object advice;
					if (this.singleton || this.beanFactory.isSingleton(name)) {
						// 将真正的Advisor/Advice添加到链中.
						advice = this.beanFactory.getBean(name);
					}
					else {
						// 这是一个prototype Advice或Advisor: 用prototype替换.
						// 避免仅为advisor链初始化而不必要地创建prototype bean.
						advice = new PrototypePlaceholderAdvisor(name);
					}
					addAdvisorOnChainCreation(advice);
				}
			}
		}

		this.advisorChainInitialized = true;
	}


	/**
	 * 返回独立advisor链.
	 * 每次返回新的prototype实例时, 我们都需要这样做,
	 * 以返回prototype Advisors和Advices的不同实例.
	 */
	private List<Advisor> freshAdvisorChain() {
		Advisor[] advisors = getAdvisors();
		List<Advisor> freshAdvisors = new ArrayList<>(advisors.length);
		for (Advisor advisor : advisors) {
			if (advisor instanceof PrototypePlaceholderAdvisor) {
				PrototypePlaceholderAdvisor pa = (PrototypePlaceholderAdvisor) advisor;
				if (logger.isDebugEnabled()) {
					logger.debug("Refreshing bean named '" + pa.getBeanName() + "'");
				}
				// 用getBean查找产生的新prototype实例替换占位符
				if (this.beanFactory == null) {
					throw new IllegalStateException("No BeanFactory available anymore (probably due to " +
							"serialization) - cannot resolve prototype advisor '" + pa.getBeanName() + "'");
				}
				Object bean = this.beanFactory.getBean(pa.getBeanName());
				Advisor refreshedAdvisor = namedBeanToAdvisor(bean);
				freshAdvisors.add(refreshedAdvisor);
			}
			else {
				// 添加共享实例.
				freshAdvisors.add(advisor);
			}
		}
		return freshAdvisors;
	}

	/**
	 * 添加所有全局interceptor和pointcut.
	 */
	private void addGlobalAdvisors(ListableBeanFactory beanFactory, String prefix) {
		String[] globalAdvisorNames =
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, Advisor.class);
		String[] globalInterceptorNames =
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, Interceptor.class);
		if (globalAdvisorNames.length > 0 || globalInterceptorNames.length > 0) {
			List<Object> beans = new ArrayList<>(globalAdvisorNames.length + globalInterceptorNames.length);
			for (String name : globalAdvisorNames) {
				if (name.startsWith(prefix)) {
					beans.add(beanFactory.getBean(name));
				}
			}
			for (String name : globalInterceptorNames) {
				if (name.startsWith(prefix)) {
					beans.add(beanFactory.getBean(name));
				}
			}
			AnnotationAwareOrderComparator.sort(beans);
			for (Object bean : beans) {
				addAdvisorOnChainCreation(bean);
			}
		}
	}

	/**
	 * 在创建advice链时调用.
	 * <p>将给定的advice、advisor或object添加到interceptor list中.
	 * 由于这三种可能性, 我们无法做更强的签名.
	 * @param next advice、adviso或target object
	 */
	private void addAdvisorOnChainCreation(Object next) {
		// 如果有必要, 我们需要转换为Advisor, 以便source引用从超类interceptor中找到的匹配.
		addAdvisor(namedBeanToAdvisor(next));
	}

	/**
	 * 返回一个在创建代理时使用的TargetSource. 如果在interceptorNames list的末尾没有指定
	 * target, 则TargetSource将是该类的TargetSource成员. 否则, 我们将获取target bean,
	 * 并在必要时将其包装在TargetSource中.
	 */
	private TargetSource freshTargetSource() {
		if (this.targetName == null) {
			// 没有刷新target: 在'interceptorNames'中没有指定bean名称
			return this.targetSource;
		}
		else {
			if (this.beanFactory == null) {
				throw new IllegalStateException("No BeanFactory available anymore (probably due to serialization) " +
						"- cannot resolve target with name '" + this.targetName + "'");
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Refreshing target with name '" + this.targetName + "'");
			}
			Object target = this.beanFactory.getBean(this.targetName);
			return (target instanceof TargetSource ? (TargetSource) target : new SingletonTargetSource(target));
		}
	}

	/**
	 * 将以下对象从对interceptorNames数组中的名称调用getBean()转换为Advisor或TargetSource.
	 */
	private Advisor namedBeanToAdvisor(Object next) {
		try {
			return this.advisorAdapterRegistry.wrap(next);
		}
		catch (UnknownAdviceTypeException ex) {
			// 我们期望这是一个Advisor或Advice, 但它不是. 这是一个配置错误.
			throw new AopConfigException("Unknown advisor type " + next.getClass() +
					"; can only include Advisor or Advice type beans in interceptorNames chain " +
					"except for last entry which may also be target instance or TargetSource", ex);
		}
	}

	/**
	 * 在advice更改时, 吹走并重新缓存singleton.
	 */
	@Override
	protected void adviceChanged() {
		super.adviceChanged();
		if (this.singleton) {
			logger.debug("Advice has changed; re-caching singleton instance");
			synchronized (this) {
				this.singletonInstance = null;
			}
		}
	}


	//---------------------------------------------------------------------
	// 序列化支持
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// 依赖默认序列化; 只需在反序列化后初始化状态.
		ois.defaultReadObject();

		// transient字段进行初始化.
		this.proxyClassLoader = ClassUtils.getDefaultClassLoader();
	}


	/**
	 * 在interceptor链中使用, 我们需要在创建代理时用prototype替换bean.
	 */
	private static class PrototypePlaceholderAdvisor implements Advisor, Serializable {

		private final String beanName;

		private final String message;

		public PrototypePlaceholderAdvisor(String beanName) {
			this.beanName = beanName;
			this.message = "Placeholder for prototype Advisor/Advice with bean name '" + beanName + "'";
		}

		public String getBeanName() {
			return this.beanName;
		}

		@Override
		public Advice getAdvice() {
			throw new UnsupportedOperationException("Cannot invoke methods: " + this.message);
		}

		@Override
		public boolean isPerInstance() {
			throw new UnsupportedOperationException("Cannot invoke methods: " + this.message);
		}

		@Override
		public String toString() {
			return this.message;
		}
	}

}
