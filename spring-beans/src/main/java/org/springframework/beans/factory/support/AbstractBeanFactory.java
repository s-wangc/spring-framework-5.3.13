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

package org.springframework.beans.factory.support;

import java.beans.PropertyEditor;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * {@link org.springframework.beans.factory.BeanFactory}实现的抽象基类, 提供了
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI的全部功能.
 * <i>not</i>假设有一个可listable的bean工厂: 因此也可以用作bean工厂实现的基类, 该实现从某个后端字段
 * (其中bean定义访问时一项昂贵的操作)获得bean定义.
 *
 * <p>这个类提供了一个singleton缓存(通过它的基类
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype确定, {@link org.springframework.beans.factory.FactoryBean}处理,
 * 别名, 子bean定义的bean definition合并和bean销毁({@link org.springframework.beans.factory.DisposableBean}
 * 接口, 自定义destroy方法). 此外, 它可以通过实现{@link org.springframework.beans.factory.HierarchicalBeanFactory}
 * 接口来管理bean工厂的层次结构(如果是未知的bean, 则将其委托给父级).
 *
 * <p>由子类实现的主要模板方法是{@link #getBeanDefinition}和{@link #createBean},
 * 分别为给定的bean名称检索bean定义, 并为给定的bean定义创建bean实例. 这些操作的默认实现
 * 可以在{@link DefaultListableBeanFactory}和{@link AbstractAutowireCapableBeanFactory}
 * 中找到.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @since 15 April 2001
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

	/** 父bean工厂, 用于支持bean继承. */
	@Nullable
	private BeanFactory parentBeanFactory;

	/** ClassLoader来解析bean类名, 如果需要的话. */
	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/** ClassLoaderr来临时解析bean类名, 如果需要的话. */
	@Nullable
	private ClassLoader tempClassLoader;

	/** 是缓存bean元数据, 还是为每次访问重新获取它. */
	private boolean cacheBeanMetadata = true;

	/** bean定义值中表达式的解析策略. */
	@Nullable
	private BeanExpressionResolver beanExpressionResolver;

	/** 使用Spring ConversionService来代替PropertyEditors. */
	@Nullable
	private ConversionService conversionService;

	/** 自定义PropertyEditorRegistrars应用于该工厂的bean. */
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars = new LinkedHashSet<>(4);

	/** 自定义PropertyEditors应用于该工厂的bean. */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors = new HashMap<>(4);

	/** 要使用的自定义TypeConverter, 覆盖默认的PropertyEditor机制. */
	@Nullable
	private TypeConverter typeConverter;

	/** 用于注解属性值的String resolvers. */
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/** 应用的BeanPostProcessors. */
	private final List<BeanPostProcessor> beanPostProcessors = new BeanPostProcessorCacheAwareList();

	/** 预过滤post-processors的缓存. */
	@Nullable
	private volatile BeanPostProcessorCache beanPostProcessorCache;

	/** 从scope标识符映射到相应的scope. */
	private final Map<String, Scope> scopes = new LinkedHashMap<>(8);

	/** 与SecurityManager一起运行时使用的Security context. */
	@Nullable
	private SecurityContextProvider securityContextProvider;

	/** 从bean名称映射到merged RootBeanDefinition. */
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

	/** 已经至少创建过一次的bean的名称. */
	private final Set<String> alreadyCreated = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	/** 当前正在创建的bean的名称. */
	private final ThreadLocal<Object> prototypesCurrentlyInCreation =
			new NamedThreadLocal<>("Prototype beans currently in creation");

	/** 应用程序启动指标. **/
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/**
	 * 创建一个新的AbstractBeanFactory.
	 */
	public AbstractBeanFactory() {
	}

	/**
	 * 使用给定的parent创建一个新AbstractBeanFactory.
	 * @param parentBeanFactory 父parent工厂, 如果没有, 则为{@code null}
	 * @see #getBean
	 */
	public AbstractBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}


	//---------------------------------------------------------------------
	// BeanFactory接口的实现
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		// get bean step01: 调用同一的doGetBean方法
		return doGetBean(name, null, null, false);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		return doGetBean(name, requiredType, null, false);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		return doGetBean(name, null, args, false);
	}

	/**
	 * 返回指定bean的一个实例, 该实例可以是共享的, 也可以是独立的.
	 * @param name 要检索的bean的名称
	 * @param requiredType 要检索的bean的所需类型
	 * @param args 在使用显式参数创建bean实例时使用的参数(仅在创建实例而不是检索现有实例时使用)
	 * @return 一个bean的实例
	 * @throws BeansException 如果无法创建bean
	 */
	public <T> T getBean(String name, @Nullable Class<T> requiredType, @Nullable Object... args)
			throws BeansException {

		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * 返回指定bean的一个实例, 该实例可以是共享的, 也可以是独立的.
	 * @param name 要检索的bean的名称
	 * @param requiredType 要检索的bean的所需类型
	 * @param args 在使用显式参数创建bean实例时使用的参数(仅在创建实例而不是检索现有实例时使用)
	 * @param typeCheckOnly 是否为类型检查而获取实例, 而不是实际使用
	 * @return 一个bean的实例
	 * @throws BeansException 如果无法创建bean
	 */
	@SuppressWarnings("unchecked")
	protected <T> T doGetBean(
			String name, @Nullable Class<T> requiredType, @Nullable Object[] args, boolean typeCheckOnly)
			throws BeansException {

		// get bean step02: 去掉工厂引用前缀, 并且将别名解析为beanName
		String beanName = transformedBeanName(name);
		Object beanInstance;

		// get bean step03: 检查一下该beanName是否存在单例实例
		// 主动检查singleton缓存中是否有手动注册的singleton.
		Object sharedInstance = getSingleton(beanName);
		if (sharedInstance != null && args == null) {
			if (logger.isTraceEnabled()) {
				if (isSingletonCurrentlyInCreation(beanName)) {
					logger.trace("Returning eagerly cached instance of singleton bean '" + beanName +
							"' that is not fully initialized yet - a consequence of a circular reference");
				}
				else {
					logger.trace("Returning cached instance of singleton bean '" + beanName + "'");
				}
			}
			// get bean step12: 如果我们获取到了这样的单例实例并且参数为null, 我们就需要通过这个单例实例获取一个bean实例了
			beanInstance = getObjectForBeanInstance(sharedInstance, name, beanName, null);
		}

		else {
			// get bean step26: 如果共享实例不存在或者参数不为空
			// 先判断一下原型实例是否正在创建中, 如果是的话就抛出异常
			// 如果我们已经创建了这个bean实例, 则会失败:
			// 我们假设在循环引用中.
			if (isPrototypeCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(beanName);
			}

			// get bean step27: 如果当前层级的工厂不存在合适的bean定义, 就去检查父工厂
			// 检查这个工厂是是否存在bean定义.
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// 未找到 -> 检查parent.
				String nameToLookup = originalBeanName(name);
				if (parentBeanFactory instanceof AbstractBeanFactory) {
					return ((AbstractBeanFactory) parentBeanFactory).doGetBean(
							nameToLookup, requiredType, args, typeCheckOnly);
				}
				else if (args != null) {
					// 用显式参数委托给parent对象.
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				}
				else if (requiredType != null) {
					// 无参数 -> 委托给标准getBean方法.
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				}
				else {
					return (T) parentBeanFactory.getBean(nameToLookup);
				}
			}

			// get bean step28: 如果这个bean是为了类型检查而获取的, 那么直接将其标记为创建中(这样会删除合并bean定义缓存)
			if (!typeCheckOnly) {
				markBeanAsCreated(beanName);
			}

			StartupStep beanCreation = this.applicationStartup.start("spring.beans.instantiate")
					.tag("beanName", name);
			try {
				if (requiredType != null) {
					beanCreation.tag("beanType", requiredType::toString);
				}
				// get bean step29: 获取合并bean定义, 并验证一下这个定义有没有毛病
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				checkMergedBeanDefinition(mbd, beanName, args);

				// get bean step30: 注册一下dependentBeanMap和dependenciesForBeanMap缓存, 并实例化所依赖的bean
				// 保证当前bean所依赖的bean的初始化.
				String[] dependsOn = mbd.getDependsOn();
				if (dependsOn != null) {
					for (String dep : dependsOn) {
						if (isDependent(beanName, dep)) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						registerDependentBean(dep, beanName);
						try {
							getBean(dep);
						}
						catch (NoSuchBeanDefinitionException ex) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"'" + beanName + "' depends on missing bean '" + dep + "'", ex);
						}
					}
				}

				// get bean step31: 如果是单例那么就先从缓存中获取, 获取不到就创建一个, 创建完在执行各种回调
				// 创建bean实例.
				if (mbd.isSingleton()) {
					sharedInstance = getSingleton(beanName, () -> {
						try {
							return createBean(beanName, mbd, args);
						}
						catch (BeansException ex) {
							// 显式地从singleton缓存中删除实例: 它可能是由创建过程中急切地放在那里,
							// 以允许循环引用解析的. 还是要删除接收到该bean临时引用的任何bean.
							destroySingleton(beanName);
							throw ex;
						}
					});
					beanInstance = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
				}

				// get bean step32: 直接创建一个并执行各种回调
				else if (mbd.isPrototype()) {
					// 这是一个prototype -> 创建一个新的实例.
					Object prototypeInstance = null;
					try {
						beforePrototypeCreation(beanName);
						prototypeInstance = createBean(beanName, mbd, args);
					}
					finally {
						afterPrototypeCreation(beanName);
					}
					beanInstance = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
				}

				// get bean step33: 如果不是singleton, 也不是prototype, 那么说明是其他自定义作用域, 需要特殊处理
				// 调用其他作用域实现来判断是否需要新创建对象, 如果需要新创建的话就调用传入的代码创建一个
				else {
					// 获取当前这个bean定义的scope
					String scopeName = mbd.getScope();
					if (!StringUtils.hasLength(scopeName)) {
						throw new IllegalStateException("No scope name defined for bean '" + beanName + "'");
					}
					// 判断一下当前这个bean所需要的scope是否有注册过, 没有注册过就抛出异常
					Scope scope = this.scopes.get(scopeName);
					if (scope == null) {
						throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
					}
					try {
						// 调用这个scope的get方法, 传入一个objectFactory, 以便在需要创建一个新bean的时候进行创建
						Object scopedInstance = scope.get(beanName, () -> {
							// 执行创建前钩子函数
							beforePrototypeCreation(beanName);
							try {
								// 进行创建工作
								return createBean(beanName, mbd, args);
							}
							finally {
								// 执行创建后回调
								afterPrototypeCreation(beanName);
							}
						});
						// 对FactoryBean进行特殊处理(判断是直接返回FactoryBean还是返回FactoryBean创建的实例)
						beanInstance = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
					}
					catch (IllegalStateException ex) {
						throw new ScopeNotActiveException(beanName, scopeName, ex);
					}
				}
			}
			catch (BeansException ex) {
				beanCreation.tag("exception", ex.getClass().toString());
				beanCreation.tag("message", String.valueOf(ex.getMessage()));
				cleanupAfterBeanCreationFailure(beanName);
				throw ex;
			}
			finally {
				beanCreation.end();
			}
		}

		// get bean step34: 对bean实例进行自适应(如果类型不是我们想要的就进行类型转换)
		return adaptBeanInstance(name, beanInstance, requiredType);
	}

	@SuppressWarnings("unchecked")
	<T> T adaptBeanInstance(String name, Object bean, @Nullable Class<?> requiredType) {
		// 检查所需类型是否与实际bean实例的类型匹配.
		if (requiredType != null && !requiredType.isInstance(bean)) {
			try {
				Object convertedBean = getTypeConverter().convertIfNecessary(bean, requiredType);
				if (convertedBean == null) {
					throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
				}
				return (T) convertedBean;
			}
			catch (TypeMismatchException ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Failed to convert bean '" + name + "' to required type '" +
							ClassUtils.getQualifiedName(requiredType) + "'", ex);
				}
				throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
			}
		}
		return (T) bean;
	}

	@Override
	public boolean containsBean(String name) {
		String beanName = transformedBeanName(name);
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// 未找到 -> 检查parent.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean) {
				return (BeanFactoryUtils.isFactoryDereference(name) || ((FactoryBean<?>) beanInstance).isSingleton());
			}
			else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}

		// 未找到singleton实例 -> 检查bean定义.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// 在这个工厂中没有找到bean定义 -> 委托给parent.
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// 对于FactoryBean, 如果不是取消引用, 则返回已创建对象的singleton状态.
		if (mbd.isSingleton()) {
			if (isFactoryBean(beanName, mbd)) {
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					return true;
				}
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				return factoryBean.isSingleton();
			}
			else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// 在这个工厂中没有找到bean定义 -> 委托给parent.
			return parentBeanFactory.isPrototype(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isPrototype()) {
			// 对于FactoryBean, 如果不是取消引用, 则返回已创建对象的singleton状态.
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
		}

		// sinelgton或scoped - 不是prototype.
		// 然而, FactoryBean仍然可以生成prototype对象.
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			return false;
		}
		if (isFactoryBean(beanName, mbd)) {
			FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(
						(PrivilegedAction<Boolean>) () ->
								((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
										!fb.isSingleton()),
						getAccessControlContext());
			}
			else {
				return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
						!fb.isSingleton());
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, typeToMatch, true);
	}

	/**
	 * {@link #isTypeMatch(String, ResolvableType)}的内部扩展变量,
	 * 以检查具有给定名称的bean是否与指定类型匹配. 允许应用额外的约束, 以确保不提前创建bean.
	 * @param name 要查询的bean的名称
	 * @param typeToMatch 要匹配的类型(作为{@code ResolvableType})
	 * @return 如果bean类型匹配, 则为{@code true}, 如果不匹配或尚未确定, 则为{@code false}
	 * @throws NoSuchBeanDefinitionException 如果没有具有给定名称的bean
	 * @since 5.2
	 * @see #getBean
	 * @see #getType
	 */
	protected boolean isTypeMatch(String name, ResolvableType typeToMatch, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		String beanName = transformedBeanName(name);
		boolean isFactoryDereference = BeanFactoryUtils.isFactoryDereference(name);

		// 检查手动注册的singleton.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			if (beanInstance instanceof FactoryBean) {
				if (!isFactoryDereference) {
					Class<?> type = getTypeForFactoryBean((FactoryBean<?>) beanInstance);
					return (type != null && typeToMatch.isAssignableFrom(type));
				}
				else {
					return typeToMatch.isInstance(beanInstance);
				}
			}
			else if (!isFactoryDereference) {
				if (typeToMatch.isInstance(beanInstance)) {
					// 公开实例的直接匹配?
					return true;
				}
				else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// 泛型可能只在目标类上匹配, 而不是在代理上匹配...
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					Class<?> targetType = mbd.getTargetType();
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance)) {
						// 同时检查原始类匹配, 确保它在代理上公开.
						Class<?> classToMatch = typeToMatch.resolve();
						if (classToMatch != null && !classToMatch.isInstance(beanInstance)) {
							return false;
						}
						if (typeToMatch.isAssignableFrom(targetType)) {
							return true;
						}
					}
					ResolvableType resolvableType = mbd.targetType;
					if (resolvableType == null) {
						resolvableType = mbd.factoryMethodReturnType;
					}
					return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
				}
			}
			return false;
		}
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// 已注册的null实例
			return false;
		}

		// 未找到singleton实例 -> 检查bean定义.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// 在这个工厂中没有找到bean定义 -> 委托给parent.
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}

		// 检索相应的bean定义.
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();

		// 设置要匹配的类型
		Class<?> classToMatch = typeToMatch.resolve();
		if (classToMatch == null) {
			classToMatch = FactoryBean.class;
		}
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[] {classToMatch} : new Class<?>[] {FactoryBean.class, classToMatch});


		// 尝试预测bean类型
		Class<?> predictedType = null;

		// 我们正在寻找一个常规引用, 但我们是一个具有修饰bean定义的工厂的bean.
		// 目标bean应该与FactoryBean最终返回的类型相同.
		if (!isFactoryDereference && dbd != null && isFactoryBean(beanName, mbd)) {
			// 只有当用户显式地将lazy-init设置为true
			// 并且我们知道merged bean定义是针对工厂bean时, 我们才应该尝试.
			if (!mbd.isLazyInit() || allowFactoryBeanInit) {
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				Class<?> targetType = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
				if (targetType != null && !FactoryBean.class.isAssignableFrom(targetType)) {
					predictedType = targetType;
				}
			}
		}

		// 如果无法使用目标类型, 请尝试常规预测.
		if (predictedType == null) {
			predictedType = predictBeanType(beanName, mbd, typesToMatch);
			if (predictedType == null) {
				return false;
			}
		}

		// 尝试获取bean的实际可解析类型.
		ResolvableType beanType = null;

		// 如果它是一个FactoryBean, 我们想看看它创建了什么, 而不是工厂类.
		if (FactoryBean.class.isAssignableFrom(predictedType)) {
			if (beanInstance == null && !isFactoryDereference) {
				beanType = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit);
				predictedType = beanType.resolve();
				if (predictedType == null) {
					return false;
				}
			}
		}
		else if (isFactoryDereference) {
			// 特例: SmartInstantiationAwareBeanPostProcessor返回了一个非FactoryBean类型,
			// 但我们仍然被要求取消对FactoryBean的引用...
			// 让我们检查原始bean类, 如果它是FactoryBean, 则继续.
			predictedType = predictBeanType(beanName, mbd, FactoryBean.class);
			if (predictedType == null || !FactoryBean.class.isAssignableFrom(predictedType)) {
				return false;
			}
		}

		// 我们没有确切的类型, 但是如果bean定义目标类型或工厂方法返回类型与预测类型匹配, 那么我们可以使用它.
		if (beanType == null) {
			ResolvableType definedType = mbd.targetType;
			if (definedType == null) {
				definedType = mbd.factoryMethodReturnType;
			}
			if (definedType != null && definedType.resolve() == predictedType) {
				beanType = definedType;
			}
		}

		// 如果我们有一个bean类型, 那么使用它, 以便考虑泛型
		if (beanType != null) {
			return typeToMatch.isAssignableFrom(beanType);
		}

		// 如果没有bean类型, 则返回到预测类型
		return typeToMatch.isAssignableFrom(predictedType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		return getType(name, true);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		// 检查手动注册的singleton.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			if (beanInstance instanceof FactoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				return getTypeForFactoryBean((FactoryBean<?>) beanInstance);
			}
			else {
				return beanInstance.getClass();
			}
		}

		// 未找到singleton实例 -> 检查bean定义.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// 在这个工厂中没有找到bean定义 -> 委托给parent.
			return parentBeanFactory.getType(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// 检查修饰decorated 定义(如果有): 我们假设确定decorated bean的类型比确定代理的类型更容易.
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
		if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
			RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
			Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
			if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
				return targetClass;
			}
		}

		Class<?> beanClass = predictBeanType(beanName, mbd);

		// 检查bean类是否正在处理FactoryBean.
		if (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass)) {
			if (!BeanFactoryUtils.isFactoryDereference(name)) {
				// 如果它是一个FactoryBean, 我们想看看它创建了什么, 而不是工厂类.
				return getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit).resolve();
			}
			else {
				return beanClass;
			}
		}
		else {
			return (!BeanFactoryUtils.isFactoryDereference(name) ? beanClass : null);
		}
	}

	@Override
	public String[] getAliases(String name) {
		String beanName = transformedBeanName(name);
		List<String> aliases = new ArrayList<>();
		boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
		String fullBeanName = beanName;
		if (factoryPrefix) {
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		if (!fullBeanName.equals(name)) {
			aliases.add(fullBeanName);
		}
		String[] retrievedAliases = super.getAliases(beanName);
		String prefix = factoryPrefix ? FACTORY_BEAN_PREFIX : "";
		for (String retrievedAlias : retrievedAliases) {
			String alias = prefix + retrievedAlias;
			if (!alias.equals(name)) {
				aliases.add(alias);
			}
		}
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null) {
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
		return StringUtils.toStringArray(aliases);
	}


	//---------------------------------------------------------------------
	// HierarchicalBeanFactory接口的实现
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// ConfigurableBeanFactory接口的实现
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		if (this == parentBeanFactory) {
			throw new IllegalStateException("Cannot set parent bean factory to self");
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
		this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
	}

	@Override
	@Nullable
	public ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setTempClassLoader(@Nullable ClassLoader tempClassLoader) {
		this.tempClassLoader = tempClassLoader;
	}

	@Override
	@Nullable
	public ClassLoader getTempClassLoader() {
		return this.tempClassLoader;
	}

	@Override
	public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
		this.cacheBeanMetadata = cacheBeanMetadata;
	}

	@Override
	public boolean isCacheBeanMetadata() {
		return this.cacheBeanMetadata;
	}

	@Override
	public void setBeanExpressionResolver(@Nullable BeanExpressionResolver resolver) {
		this.beanExpressionResolver = resolver;
	}

	@Override
	@Nullable
	public BeanExpressionResolver getBeanExpressionResolver() {
		return this.beanExpressionResolver;
	}

	@Override
	public void setConversionService(@Nullable ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@Override
	@Nullable
	public ConversionService getConversionService() {
		return this.conversionService;
	}

	@Override
	public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
		Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
		this.propertyEditorRegistrars.add(registrar);
	}

	/**
	 * 返回PropertyEditorRegistrars的集合.
	 */
	public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
		return this.propertyEditorRegistrars;
	}

	@Override
	public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
		Assert.notNull(requiredType, "Required type must not be null");
		Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
		this.customEditors.put(requiredType, propertyEditorClass);
	}

	@Override
	public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
		registerCustomEditors(registry);
	}

	/**
	 * 返回自定义editors, class作为key, PropertyEditor作为value.
	 */
	public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
		return this.customEditors;
	}

	@Override
	public void setTypeConverter(TypeConverter typeConverter) {
		this.typeConverter = typeConverter;
	}

	/**
	 * 返回要使用的TypeConverter(如果有).
	 * @return 自定义TypeConverter, 如果未指定, 则为{@code null}
	 */
	@Nullable
	protected TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	@Override
	public TypeConverter getTypeConverter() {
		TypeConverter customConverter = getCustomTypeConverter();
		if (customConverter != null) {
			return customConverter;
		}
		else {
			// 构建默认的TypeConverter, 注册自定义editors.
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			typeConverter.setConversionService(getConversionService());
			registerCustomEditors(typeConverter);
			return typeConverter;
		}
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		return !this.embeddedValueResolvers.isEmpty();
	}

	@Override
	@Nullable
	public String resolveEmbeddedValue(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String result = value;
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			result = resolver.resolveStringValue(result);
			if (result == null) {
				return null;
			}
		}
		return result;
	}

	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		// 如果有的话, 从原来的位置移除
		this.beanPostProcessors.remove(beanPostProcessor);
		// 添加到List的末尾
		this.beanPostProcessors.add(beanPostProcessor);
	}

	/**
	 * 添加新的BeanPostProcessors, 它将应用于该工厂创建的bean. 在工厂配置期间调用.
	 * @since 5.3
	 * @see #addBeanPostProcessor
	 */
	public void addBeanPostProcessors(Collection<? extends BeanPostProcessor> beanPostProcessors) {
		this.beanPostProcessors.removeAll(beanPostProcessors);
		this.beanPostProcessors.addAll(beanPostProcessors);
	}

	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
	}

	/**
	 * 返回将应用于使用该工厂创建的bean的BeanPostProcessors List.
	 */
	public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	/**
	 * 返回预过滤post-processors的内部缓存, 如有必要则重新构建.
	 * @since 5.3
	 */
	BeanPostProcessorCache getBeanPostProcessorCache() {
		BeanPostProcessorCache bpCache = this.beanPostProcessorCache;
		if (bpCache == null) {
			bpCache = new BeanPostProcessorCache();
			for (BeanPostProcessor bp : this.beanPostProcessors) {
				if (bp instanceof InstantiationAwareBeanPostProcessor) {
					bpCache.instantiationAware.add((InstantiationAwareBeanPostProcessor) bp);
					if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
						bpCache.smartInstantiationAware.add((SmartInstantiationAwareBeanPostProcessor) bp);
					}
				}
				if (bp instanceof DestructionAwareBeanPostProcessor) {
					bpCache.destructionAware.add((DestructionAwareBeanPostProcessor) bp);
				}
				if (bp instanceof MergedBeanDefinitionPostProcessor) {
					bpCache.mergedDefinition.add((MergedBeanDefinitionPostProcessor) bp);
				}
			}
			this.beanPostProcessorCache = bpCache;
		}
		return bpCache;
	}

	/**
	 * 返回该工厂是否持有一个InstantiationAwareBeanPostProcessor,
	 * 该处理器将在创建时应用到singleton bean.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
	 */
	protected boolean hasInstantiationAwareBeanPostProcessors() {
		return !getBeanPostProcessorCache().instantiationAware.isEmpty();
	}

	/**
	 * 返回该工厂是否持有一个DestructionAwareBeanPostProcessor,
	 * 它将在关闭时应用于singleton bean.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean hasDestructionAwareBeanPostProcessors() {
		return !getBeanPostProcessorCache().destructionAware.isEmpty();
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		Assert.notNull(scope, "Scope must not be null");
		// 不允许注册名为singleton、prototype的Scope
		if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
			throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
		}
		// 把Scope放入到Map当中(判断是否发生了作用域替换)
		Scope previous = this.scopes.put(scopeName, scope);
		if (previous != null && previous != scope) {
			if (logger.isDebugEnabled()) {
				logger.debug("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
			}
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
			}
		}
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return StringUtils.toStringArray(this.scopes.keySet());
	}

	@Override
	@Nullable
	public Scope getRegisteredScope(String scopeName) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		return this.scopes.get(scopeName);
	}

	/**
	 * 为这个bean工厂设置security context privoder.
	 * 如果设置了security context, 则将使用所提供的security context的特权执行与用户代码的交互.
	 */
	public void setSecurityContextProvider(SecurityContextProvider securityProvider) {
		this.securityContextProvider = securityProvider;
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "applicationStartup should not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	/**
	 * 将访问控制context的创建委托给
	 * {@link #setSecurityContextProvider SecurityContextProvider}.
	 */
	@Override
	public AccessControlContext getAccessControlContext() {
		return (this.securityContextProvider != null ?
				this.securityContextProvider.getAccessControlContext() :
				AccessController.getContext());
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		Assert.notNull(otherFactory, "BeanFactory must not be null");
		setBeanClassLoader(otherFactory.getBeanClassLoader());
		setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
		setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
		setConversionService(otherFactory.getConversionService());
		if (otherFactory instanceof AbstractBeanFactory) {
			AbstractBeanFactory otherAbstractFactory = (AbstractBeanFactory) otherFactory;
			this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
			this.customEditors.putAll(otherAbstractFactory.customEditors);
			this.typeConverter = otherAbstractFactory.typeConverter;
			this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
			this.scopes.putAll(otherAbstractFactory.scopes);
			this.securityContextProvider = otherAbstractFactory.securityContextProvider;
		}
		else {
			setTypeConverter(otherFactory.getTypeConverter());
			String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
			for (String scopeName : otherScopeNames) {
				this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
			}
		}
	}

	/**
	 * 返回给定bean名称的'merged' BeanDefinition, 必要时将子bean定义与其父bean定义合并.
	 * <p>这个{@code getMergedBeanDefinition}还考虑了祖先中的bean定义.
	 * @param name 要检索merged定义的bean的名称(可能是别名)
	 * @return 给定bean的(可能是merged)RootBeanDefinition
	 * @throws NoSuchBeanDefinitionException 如果没有具有给定名称的bean
	 * @throws BeanDefinitionStoreException 在bean定义无效的情况下
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		String beanName = transformedBeanName(name);
		// 高效地检查该工厂中是否存在bean定义.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) getParentBeanFactory()).getMergedBeanDefinition(beanName);
		}
		// 在本地解析合并的bean定义.
		return getMergedLocalBeanDefinition(beanName);
	}

	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			return (beanInstance instanceof FactoryBean);
		}
		// 未找到singleton实例 -> 检查bean定义.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			// 在这个工厂中没有找到bean定义 -> 委托给parent.
			return ((ConfigurableBeanFactory) getParentBeanFactory()).isFactoryBean(name);
		}
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	@Override
	public boolean isActuallyInCreation(String beanName) {
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * 返回指定的prototype bean是否正在创建中(在当前线程内).
	 * @param beanName bean的名称
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set && ((Set<?>) curVal).contains(beanName))));
	}

	/**
	 * 在创建prototype之前回调.
	 * <p>默认实现将prototype注册为当前正在创建的状态.
	 * @param beanName 将要创建的prototype的名称
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void beforePrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal == null) {
			this.prototypesCurrentlyInCreation.set(beanName);
		}
		else if (curVal instanceof String) {
			Set<String> beanNameSet = new HashSet<>(2);
			beanNameSet.add((String) curVal);
			beanNameSet.add(beanName);
			this.prototypesCurrentlyInCreation.set(beanNameSet);
		}
		else {
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.add(beanName);
		}
	}

	/**
	 * 创建prototype后的回调.
	 * <p>默认实现将prototype标记为不在创建中.
	 * @param beanName 已创建的prototype的名称
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void afterPrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal instanceof String) {
			this.prototypesCurrentlyInCreation.remove();
		}
		else if (curVal instanceof Set) {
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.remove(beanName);
			if (beanNameSet.isEmpty()) {
				this.prototypesCurrentlyInCreation.remove();
			}
		}
	}

	@Override
	public void destroyBean(String beanName, Object beanInstance) {
		destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * 根据给定的bean定义销毁给定的bean实例(通常是从该工厂获得的prototype实例).
	 * @param beanName bean定义的名称
	 * @param bean 要销毁的bean实例
	 * @param mbd merged bean定义
	 */
	protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
		new DisposableBeanAdapter(
				bean, beanName, mbd, getBeanPostProcessorCache().destructionAware, getAccessControlContext()).destroy();
	}

	@Override
	public void destroyScopedBean(String beanName) {
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isSingleton() || mbd.isPrototype()) {
			throw new IllegalArgumentException(
					"Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
		}
		String scopeName = mbd.getScope();
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
		}
		Object bean = scope.remove(beanName);
		if (bean != null) {
			destroyBean(beanName, bean, mbd);
		}
	}


	//---------------------------------------------------------------------
	// 实现方法
	//---------------------------------------------------------------------

	/**
	 * 返回bean名称, 必要时去掉工厂解引用前缀, 并将别名解析为规范名称.
	 * @param name 指定的名称
	 * @return 转换后的bean名称
	 */
	protected String transformedBeanName(String name) {
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * 确定原始bean名称, 将本地定义的别名解析为规范名称.
	 * @param name 指定的名称
	 * @return 原来的bean名称
	 */
	protected String originalBeanName(String name) {
		String beanName = transformedBeanName(name);
		if (name.startsWith(FACTORY_BEAN_PREFIX)) {
			beanName = FACTORY_BEAN_PREFIX + beanName;
		}
		return beanName;
	}

	/**
	 * 使用向该工厂注册的自定义editors初始化给定的BeanWrapper.
	 *用于创建和填充bean实例的BeanWrappers调用.
	 * <p>默认实现委托给{@link #registerCustomEditors}.
	 * 可以在子类中重写.
	 * @param bw 要初始化的BeanWrapper
	 */
	protected void initBeanWrapper(BeanWrapper bw) {
		bw.setConversionService(getConversionService());
		registerCustomEditors(bw);
	}

	/**
	 * 使用已经注册到这个BeanFactory的自定义editors初始化给定的PropertyEditorRegistry.
	 * <p>用于创建和填充bean实例的BeanWrappers, 以及用于构造函数参数和工厂方法类型转换的SimpleTypeConverter.
	 * @param registry 要初始化的PropertyEditorRegistry
	 */
	protected void registerCustomEditors(PropertyEditorRegistry registry) {
		if (registry instanceof PropertyEditorRegistrySupport) {
			((PropertyEditorRegistrySupport) registry).useConfigValueEditors();
		}
		if (!this.propertyEditorRegistrars.isEmpty()) {
			for (PropertyEditorRegistrar registrar : this.propertyEditorRegistrars) {
				try {
					registrar.registerCustomEditors(registry);
				}
				catch (BeanCreationException ex) {
					Throwable rootCause = ex.getMostSpecificCause();
					if (rootCause instanceof BeanCurrentlyInCreationException) {
						BeanCreationException bce = (BeanCreationException) rootCause;
						String bceBeanName = bce.getBeanName();
						if (bceBeanName != null && isCurrentlyInCreation(bceBeanName)) {
							if (logger.isDebugEnabled()) {
								logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
										"] failed because it tried to obtain currently created bean '" +
										ex.getBeanName() + "': " + ex.getMessage());
							}
							onSuppressedException(ex);
							continue;
						}
					}
					throw ex;
				}
			}
		}
		if (!this.customEditors.isEmpty()) {
			this.customEditors.forEach((requiredType, editorClass) ->
					registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass)));
		}
	}


	/**
	 * 返回merged RootBeanDefinition, 如果指定的bean对应于子bean定义, 则遍历父bean定义.
	 * @param beanName 要检索其merged定义的bean的名称
	 * @return 给定bean的(可能是merged)RootBeanDefinition
	 * @throws NoSuchBeanDefinitionException 如果没有具有给定名称的bean
	 * @throws BeanDefinitionStoreException 在bean定义无效的情况下
	 */
	protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
		// 首先快速检查并发映射, 使用最少的锁.
		RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
		if (mbd != null && !mbd.stale) {
			return mbd;
		}
		return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
	}

	/**
	 * 通过与父bean合并(如果给定bean的定义是子bean定义), 返回给定顶级bean的RootBeanDefinition.
	 * @param beanName bean定义的名称
	 * @param bd 原始bean定义(Root/ChildBeanDefinition)
	 * @return 给定bean的(可能是merged)RootBeanDefinition
	 * @throws BeanDefinitionStoreException 在bean定义无效的情况下
	 */
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {

		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * 如果给定bean的定义是子bean定义, 通过与父bean合并, 返回给定bean的RootBeanDefinition.
	 * @param beanName bean定义的名称
	 * @param bd 原始bean定义(Root/ChildBeanDefinition)
	 * @param containingBd 对于内部bean, 包含bean定义; 对于顶级bean, 包含{@code null}
	 * @return 给定bean的(可能是merged)RootBeanDefinition
	 * @throws BeanDefinitionStoreException 在bean定义无效的情况下
	 */
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, @Nullable BeanDefinition containingBd)
			throws BeanDefinitionStoreException {

		synchronized (this.mergedBeanDefinitions) {
			RootBeanDefinition mbd = null;
			RootBeanDefinition previous = null;

			// 现在检查完全锁定, 以便强制执行相同的merged实例.
			if (containingBd == null) {
				mbd = this.mergedBeanDefinitions.get(beanName);
			}

			if (mbd == null || mbd.stale) {
				previous = mbd;
				if (bd.getParentName() == null) {
					// 使用给定root bean定义的副本.
					if (bd instanceof RootBeanDefinition) {
						mbd = ((RootBeanDefinition) bd).cloneBeanDefinition();
					}
					else {
						mbd = new RootBeanDefinition(bd);
					}
				}
				else {
					// 子bean定义: 需要与父bean合并.
					BeanDefinition pbd;
					try {
						String parentBeanName = transformedBeanName(bd.getParentName());
						if (!beanName.equals(parentBeanName)) {
							pbd = getMergedBeanDefinition(parentBeanName);
						}
						else {
							BeanFactory parent = getParentBeanFactory();
							if (parent instanceof ConfigurableBeanFactory) {
								pbd = ((ConfigurableBeanFactory) parent).getMergedBeanDefinition(parentBeanName);
							}
							else {
								throw new NoSuchBeanDefinitionException(parentBeanName,
										"Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
												"': cannot be resolved without a ConfigurableBeanFactory parent");
							}
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
								"Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
					}
					// 具有覆盖值的深度复制.
					mbd = new RootBeanDefinition(pbd);
					mbd.overrideFrom(bd);
				}

				// 如果之前未配置, 则设置默认singleton scope.
				if (!StringUtils.hasLength(mbd.getScope())) {
					mbd.setScope(SCOPE_SINGLETON);
				}

				// 包含在non-singleton bean中的bean 不能是singleton本身.
				// 让我们在这里动态纠正这个问题, 因为这可能是外部bean的父子合并的结果,
				// 在这种情况下, 原始内部bean定义将不会继承合并的外部bean的singleton状态.
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					mbd.setScope(containingBd.getScope());
				}

				// 暂时缓存合并的bean定义(稍后可能会重新合并, 以获取元数据更改)
				if (containingBd == null && isCacheBeanMetadata()) {
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}
			if (previous != null) {
				copyRelevantMergedBeanDefinitionCaches(previous, mbd);
			}
			return mbd;
		}
	}

	private void copyRelevantMergedBeanDefinitionCaches(RootBeanDefinition previous, RootBeanDefinition mbd) {
		if (ObjectUtils.nullSafeEquals(mbd.getBeanClassName(), previous.getBeanClassName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryBeanName(), previous.getFactoryBeanName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryMethodName(), previous.getFactoryMethodName())) {
			ResolvableType targetType = mbd.targetType;
			ResolvableType previousTargetType = previous.targetType;
			if (targetType == null || targetType.equals(previousTargetType)) {
				mbd.targetType = previousTargetType;
				mbd.isFactoryBean = previous.isFactoryBean;
				mbd.resolvedTargetType = previous.resolvedTargetType;
				mbd.factoryMethodReturnType = previous.factoryMethodReturnType;
				mbd.factoryMethodToIntrospect = previous.factoryMethodToIntrospect;
			}
		}
	}

	/**
	 * 检查给定的merged bean定义, 可能会引发验证异常.
	 * @param mbd 要检查的merged bean定义
	 * @param beanName bean的名称
	 * @param args 创建bean的参数(如果有)
	 * @throws BeanDefinitionStoreException 如果验证失败
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object[] args)
			throws BeanDefinitionStoreException {

		if (mbd.isAbstract()) {
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * 删除指定bean的合并bean定义, 并在下次访问时重新创建它.
	 * @param beanName 要清除合并定义的bean名称
	 */
	protected void clearMergedBeanDefinition(String beanName) {
		RootBeanDefinition bd = this.mergedBeanDefinitions.get(beanName);
		if (bd != null) {
			bd.stale = true;
		}
	}

	/**
	 * 清除合并的bean定义缓存, 删除尚未被认为符合完全元数据缓存缓存条件的bean条目.
	 * <p>通常在改变原始bean定义之后触发, 例如在应用{@code BeanFactoryPostProcessor}之后.
	 * 注意, 此时已经创建的吧ean的元数据将被保留.
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		this.mergedBeanDefinitions.forEach((beanName, bd) -> {
			if (!isBeanEligibleForMetadataCaching(beanName)) {
				bd.stale = true;
			}
		});
	}

	/**
	 * 未指定的bean定义解析bean类, 将bean类名解析为Class引用(如果需要),
	 * 并将解析后的class存储在bean定义中以供进一步使用.
	 * @param mbd 用于确定类的合并bean定义
	 * @param beanName bean的名称(用于错误处理)
	 * @param typesToMatch 在内部类型匹配的情况下匹配的类型
	 * (还表明返回的{@code Class}永远不会暴露给应用程序代码)
	 * @return 解析的bean类(如果没有, 则为{@code null})
	 * @throws CannotLoadBeanClassException 如果加载类失败
	 */
	@Nullable
	protected Class<?> resolveBeanClass(RootBeanDefinition mbd, String beanName, Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {

		try {
			if (mbd.hasBeanClass()) {
				return mbd.getBeanClass();
			}
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedExceptionAction<Class<?>>)
						() -> doResolveBeanClass(mbd, typesToMatch), getAccessControlContext());
			}
			else {
				return doResolveBeanClass(mbd, typesToMatch);
			}
		}
		catch (PrivilegedActionException pae) {
			ClassNotFoundException ex = (ClassNotFoundException) pae.getException();
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (LinkageError err) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
	}

	@Nullable
	private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {

		ClassLoader beanClassLoader = getBeanClassLoader();
		ClassLoader dynamicLoader = beanClassLoader;
		boolean freshResolve = false;

		if (!ObjectUtils.isEmpty(typesToMatch)) {
			// 当只做类型检查时(例如, 还没有创建实际的实例),
			// 使用指定的临时类加载器(例如, 在weave场景中).
			ClassLoader tempClassLoader = getTempClassLoader();
			if (tempClassLoader != null) {
				dynamicLoader = tempClassLoader;
				freshResolve = true;
				if (tempClassLoader instanceof DecoratingClassLoader) {
					DecoratingClassLoader dcl = (DecoratingClassLoader) tempClassLoader;
					for (Class<?> typeToMatch : typesToMatch) {
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}

		String className = mbd.getBeanClassName();
		if (className != null) {
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			if (!className.equals(evaluated)) {
				// 动态解析表达式, 4.2版本支持...
				if (evaluated instanceof Class) {
					return (Class<?>) evaluated;
				}
				else if (evaluated instanceof String) {
					className = (String) evaluated;
					freshResolve = true;
				}
				else {
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			if (freshResolve) {
				// 在解析临时类加载器, 应尽早退出, 以避免将解析后的类存储在bean定义中.
				if (dynamicLoader != null) {
					try {
						return dynamicLoader.loadClass(className);
					}
					catch (ClassNotFoundException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Could not load class [" + className + "] from " + dynamicLoader + ": " + ex);
						}
					}
				}
				return ClassUtils.forName(className, dynamicLoader);
			}
		}

		// 定期解析, 在BeanDefinition中缓存结果...
		return mbd.resolveBeanClass(beanClassLoader);
	}

	/**
	 * 计算bean定义中包含的给定String, 可能将其解析为表达式.
	 * @param value 要检查的value
	 * @param beanDefinition 值来自的bean定义
	 * @return 解析后的value
	 * @see #setBeanExpressionResolver
	 */
	@Nullable
	protected Object evaluateBeanDefinitionString(@Nullable String value, @Nullable BeanDefinition beanDefinition) {
		if (this.beanExpressionResolver == null) {
			return value;
		}

		Scope scope = null;
		if (beanDefinition != null) {
			String scopeName = beanDefinition.getScope();
			if (scopeName != null) {
				scope = getRegisteredScope(scopeName);
			}
		}
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * 预测指定bean的(已处理bean实例的)最终bean类型. 由{@link #getType}和
	 * {@link #isTypeMatch}调用. 不需要专门处理FactoryBeans,
	 * 因为它只应该对原始bean类型进行操作.
	 * <p>这个实现是简单的, 因为它不能处理工厂方法和InstantiationAwareBeanPostProcessors.
	 * 它只能正确预测标准bean的bean类型. 在子类中重写, 应用更复杂的类型检查.
	 * @param beanName bean的名称
	 * @param mbd 用于确定类型的merged bean定义
	 * @param typesToMatch 在内部类型匹配的情况下匹配的类型
	 * (还表明返回的{@code Class}永远不会暴露给应用程序代码)
	 * @return bean的类型, 如果不能预测, 则为{@code null}
	 */
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType != null) {
			return targetType;
		}
		if (mbd.getFactoryMethodName() != null) {
			return null;
		}
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * 检查给定的bean是否被定义为{@link FactoryBean}.
	 * @param beanName bean的名称
	 * @param mbd 对应的bean定义
	 */
	protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
		Boolean result = mbd.isFactoryBean;
		if (result == null) {
			Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
			result = (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
			mbd.isFactoryBean = result;
		}
		return result;
	}

	/**
	 * 尽可能确定给定的FactoryBean定义的bean类型.
	 * 只有在没有为目标bean注册的singleton实例时才调用. 如果{@code allowInit}是{@code true}
	 * 且不能通过其他方式确定类型, 则允许实例化目标工程bean; 否则, 它仅限于内省签名和相关元数据.
	 * <p>如果在bean定义上没有设置{@link FactoryBean#OBJECT_TYPE_ATTRIBUTE}, 并且
	 * {@code allowInit}是{@code true}, 默认实现将通过{@code getBean}创建FactoryBean
	 * 来调用它的{@code getObjectType}方法. 鼓励子类对此进行优化, 通常通过检查工厂bean类的
	 * 通用签名或创建它的工厂方法. 如果子类确实实例化了FactoryBean, 它们应该考虑在不完全填充bean
	 * 的情况下尝试{@code getObjectType}方法. 如果此操作失败, 则应使用由该实现执行的完整FactoryBean
	 * 创建作为回退.
	 * @param beanName bean的名称
	 * @param mbd  bean的merged bean定义
	 * @param allowInit 如果不能通过另一种方式确定类型, 则允许FactoryBean的初始化
	 * @return bean的类型(如果可以确定), 否则为{@code ResolvableType.NONE}
	 * @since 5.2
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 */
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		if (allowInit && mbd.isSingleton()) {
			try {
				FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
				Class<?> objectType = getTypeForFactoryBean(factoryBean);
				return (objectType != null ? ResolvableType.forClass(objectType) : ResolvableType.NONE);
			}
			catch (BeanCreationException ex) {
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					logger.trace(LogMessage.format("Bean currently in creation on FactoryBean type check: %s", ex));
				}
				else if (mbd.isLazyInit()) {
					logger.trace(LogMessage.format("Bean creation exception on lazy FactoryBean type check: %s", ex));
				}
				else {
					logger.debug(LogMessage.format("Bean creation exception on eager FactoryBean type check: %s", ex));
				}
				onSuppressedException(ex);
			}
		}
		return ResolvableType.NONE;
	}

	/**
	 * 通过检查FactoryBean的{@link FactoryBean#OBJECT_TYPE_ATTRIBUTE}值属性来确定它的bean类型.
	 * @param attributes 要检查的属性
	 * @return 从属性或{@code ResolvableType.NONE}中提取的{@link ResolvableType}
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		if (attribute instanceof ResolvableType) {
			return (ResolvableType) attribute;
		}
		if (attribute instanceof Class) {
			return ResolvableType.forClass((Class<?>) attribute);
		}
		return ResolvableType.NONE;
	}

	/**
	 * 尽可能确定给定的FactoryBean定义的bean类型.
	 * 只有在没有为目标bean注册的singleton实例时才调用.
	 * <p>默认实现通过{@code getObjectType}创建FactoryBean来调用它的{@code getObjectType}方法.
	 * 鼓励子类对此进行优化, 通常只实例化FactoryBean, 但不填充, 尝试它的{@code getObjectType}方法
	 * 是否已经返回类型. 如果没有找到类型, 则应该使用由该实现执行的完整FactoryBean创建作为回退.
	 * @param beanName bean的名称
	 * @param mbd  bean的merged bean定义
	 * @return bean的类型(如果是可确定的), 否则为{@code null}
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 * @deprecated 从5.2开始支持{@link #getTypeForFactoryBean(String, RootBeanDefinition, boolean)}
	 */
	@Nullable
	@Deprecated
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * 将指定的bean标记为已经创建(或即将创建).
	 * <p>这允许bean工厂优化其缓存, 以重复创建指定的bean.
	 * @param beanName bean的名称
	 */
	protected void markBeanAsCreated(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			synchronized (this.mergedBeanDefinitions) {
				if (!this.alreadyCreated.contains(beanName)) {
					// 既然我们正在实际创建bean, 那么让bean定义重新合并...以防它的元数据在此期间发生变化.
					clearMergedBeanDefinition(beanName);
					this.alreadyCreated.add(beanName);
				}
			}
		}
	}

	/**
	 * 在bean创建失败后, 对缓存的元数据执行适当的清理.
	 * @param beanName bean的名称
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		synchronized (this.mergedBeanDefinitions) {
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * 确定指定的bean是否符合缓存器bean定义元数据的条件.
	 * @param beanName bean的名称
	 * @return {@code true}, 如果bean的元数据此时可能已经缓存
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * 删除给定bean名的singleton实例(如果有的话),
	 * 但只有在它没有用于类型检查以外的其他目的时才可以删除.
	 * @param beanName bean的名称
	 * @return {@code true}如果实际移除, 否则{@code false}
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			removeSingleton(beanName);
			return true;
		}
		else {
			return false;
		}
	}

	/**
	 * 检查该工厂的bean创建阶段是否已经开始, 即是否有任何bean在此期间被标记为已创建.
	 * @since 4.2.2
	 * @see #markBeanAsCreated
	 */
	protected boolean hasBeanCreationStarted() {
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * 获取给定bean实例的对象, 对于FactoryBean, 可以是bean实例本身, 也可以是它创建的对象.
	 * @param beanInstance 共享bean实例
	 * @param name 可能包含工厂解引用前缀的名称
	 * @param beanName 规范bean名称
	 * @param mbd merged bean定义
	 * @return 要为bean公开的对象
	 */
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// get bean step13: 首先我们根据name来判断一下这个被获取的bean是一个工厂吗?
		// 如果bean不是工厂, 不要让调用代码尝试接触对工厂的引用.
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			// get bean step14: 如果我们想要的bean是一个工厂, 那么我们先判断一下获取到的共享实例是什么?
			// 如果是一个NullBean直接返回去就好了
			// 如果根据name判断出来是一个工厂, 实例上共享实例又不是FactoryBean的实现, 那就说明这个配置有问题, 直接抛出异常就可以了
			if (beanInstance instanceof NullBean) {
				return beanInstance;
			}
			if (!(beanInstance instanceof FactoryBean)) {
				throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
			}
			// get bean step15: 如果上面都搞完了还没有问题的话就设置一下BeanDefinition里面的isFactoryBean属性, 然后直接把东西抛出去
			if (mbd != null) {
				mbd.isFactoryBean = true;
			}
			return beanInstance;
		}

		// get bean step16: 如果我们根据name来判断这个bean不是一个工厂引用, 并且这个bean也不是FactoryBean, 那就直接抛出去吧
		// 现在我们有了bean实例, 它可能是一个普通bean或一个FactoryBean.
		// 如果它是FactoryBean, 我们使用它来创建一个bean实例, 除非调用者实际上想要一个对工厂的引用.
		if (!(beanInstance instanceof FactoryBean)) {
			return beanInstance;
		}

		// get bean step17: 如果最终这个bean是一个FactoryBean实现, 并且BeanDefinition也不是null,
		// 那我们就设置一下BeanDefinition里面的isFactoryBean属性
		Object object = null;
		if (mbd != null) {
			mbd.isFactoryBean = true;
		}
		else {
			// get bean step18: 如果BeanDefinition为null, 那我们试着从工厂bean缓存中获取一个工厂bean
			object = getCachedObjectForFactoryBean(beanName);
		}
		if (object == null) {
			// get bean step19: 如果我们没有获取到工厂bean的话, 直接把传入的bean实例作为工厂bean就好了.
			// 然后我们检查一下这个工厂中到底存不存在这个名称的bean定义, 存在的话就直接拿出一个merged RootBeanDefinition来.
			// 取出来也仅仅只是想判断一下isSynthetic
			// 从工厂返回bean实例.
			FactoryBean<?> factory = (FactoryBean<?>) beanInstance;
			// 缓存从FactoryBean获取的对象, 如果它是singleton.
			if (mbd == null && containsBeanDefinition(beanName)) {
				mbd = getMergedLocalBeanDefinition(beanName);
			}
			boolean synthetic = (mbd != null && mbd.isSynthetic());
			// get bean step20: 从FactoryBean中获取对象
			object = getObjectFromFactoryBean(factory, beanName, !synthetic);
		}
		return object;
	}

	/**
	 * 确定给定bean名称是否已经在该工厂中使用,
	 * 即是否有在此名称下注册的本地bean或别名, 或使用此名称创建的内部bean.
	 * @param beanName 要检查的name
	 */
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * 确定给定bean是否需要在关闭时销毁.
	 * <p>默认实现检查DisposableBean接口以及制定的destroy方法和注册的DestructionAwareBeanPostProcessors.
	 * @param bean 要检查的bean实例
	 * @param mbd 对应的bean定义
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		return (bean.getClass() != NullBean.class && (DisposableBeanAdapter.hasDestroyMethod(bean, mbd) ||
				(hasDestructionAwareBeanPostProcessors() && DisposableBeanAdapter.hasApplicableProcessors(
						bean, getBeanPostProcessorCache().destructionAware))));
	}

	/**
	 * 将给定的bean添加到该工厂中的一次性bean列表中, 注册它的DisposableBean接口 and/or在工厂
	 * 关闭时调用给定的destroy方法(如果适用的话). 仅适用于singleton.
	 * @param beanName bean的名称
	 * @param bean bean实例
	 * @param mbd bean的bean definition
	 * @see RootBeanDefinition#isSingleton
	 * @see RootBeanDefinition#getDependsOn
	 * @see #registerDisposableBean
	 * @see #registerDependentBean
	 */
	protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
		AccessControlContext acc = (System.getSecurityManager() != null ? getAccessControlContext() : null);
		if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
			if (mbd.isSingleton()) {
				// 注册一个为给定bean执行所有destroy工作的DisposableBean实现:
				// DestructionAwareBeanPostProcessors、DisposableBean接口, 自定义destroy方法.
				registerDisposableBean(beanName, new DisposableBeanAdapter(
						bean, beanName, mbd, getBeanPostProcessorCache().destructionAware, acc));
			}
			else {
				// 具有自定义scope bean...
				Scope scope = this.scopes.get(mbd.getScope());
				if (scope == null) {
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
				scope.registerDestructionCallback(beanName, new DisposableBeanAdapter(
						bean, beanName, mbd, getBeanPostProcessorCache().destructionAware, acc));
			}
		}
	}


	//---------------------------------------------------------------------
	// Abstract methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * 检查此bean工厂是否包含具有给定名称的bean定义.
	 * 不考虑该工厂可能参与的任何层次结构.
	 * 当没有找到缓存的singleton实例时, 由{@code containsBean}调用.
	 * <p>根据具体bean工厂实现的性质, 此操作可能代价高昂(例如, 由于在外部注册中心进行目录查找).
	 * 然而, 对于listable的bean工厂, 这通常只相当于一个本地hash查找: 因此该操作是那里公共接口的一部分.
	 * 在这种情况下, 这个模板方法和public接口方法都可以使用相同的实现.
	 * @param beanName 要查找的bean的名称
	 * @return 如果此bean工厂包含具有给定名称的bean定义
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * 返回给定bean名称的bean定义.
	 * 子类通常应该实现缓存, 因为每次需要ban定义元数据时该类都会调用此方法.
	 * <p>根据具体bean工厂实现的性质, 此操作可能代价高昂(例如, 由于在外部注册中心进行目录查找)
	 * 然而, 对于listable的bean工厂, 这通常只相当于一个本地hash查找: 因此该操作是那里公共接口的一部分.
	 * 在这种情况下, 这个模板方法和public接口方法都可以使用相同的实现.
	 * @param beanName 要查找其定义的bean的名称
	 * @return 这个prototype名称的BeanDefinition(不能是{@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException
	 * 如果bean定义无法解析
	 * @throws BeansException 如果出现错误
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * 为给定的merged ben定义(和参数)创建一个bean实例.
	 * 如果有子定义, 则bean定义已经与parent定义merged.
	 * <p>所有的bean检索方法都委托给这个方法来进行实际的bean创建.
	 * @param beanName bean的名称
	 * @param mbd  bean的merged bean定义
	 * @param args 用于constructor或factory方法调用的显式参数
	 * @return bean的一个新实例
	 * @throws BeanCreationException 如果无法创建bean
	 */
	protected abstract Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException;


	/**
	 * CopyOnWriteArrayList, 在修改时重置beanPostProcessorCache字段.
	 *
	 * @since 5.3
	 */
	private class BeanPostProcessorCacheAwareList extends CopyOnWriteArrayList<BeanPostProcessor> {

		@Override
		public BeanPostProcessor set(int index, BeanPostProcessor element) {
			BeanPostProcessor result = super.set(index, element);
			beanPostProcessorCache = null;
			return result;
		}

		@Override
		public boolean add(BeanPostProcessor o) {
			boolean success = super.add(o);
			beanPostProcessorCache = null;
			return success;
		}

		@Override
		public void add(int index, BeanPostProcessor element) {
			super.add(index, element);
			beanPostProcessorCache = null;
		}

		@Override
		public BeanPostProcessor remove(int index) {
			BeanPostProcessor result = super.remove(index);
			beanPostProcessorCache = null;
			return result;
		}

		@Override
		public boolean remove(Object o) {
			boolean success = super.remove(o);
			if (success) {
				beanPostProcessorCache = null;
			}
			return success;
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			boolean success = super.removeAll(c);
			if (success) {
				beanPostProcessorCache = null;
			}
			return success;
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			boolean success = super.retainAll(c);
			if (success) {
				beanPostProcessorCache = null;
			}
			return success;
		}

		@Override
		public boolean addAll(Collection<? extends BeanPostProcessor> c) {
			boolean success = super.addAll(c);
			if (success) {
				beanPostProcessorCache = null;
			}
			return success;
		}

		@Override
		public boolean addAll(int index, Collection<? extends BeanPostProcessor> c) {
			boolean success = super.addAll(index, c);
			if (success) {
				beanPostProcessorCache = null;
			}
			return success;
		}

		@Override
		public boolean removeIf(Predicate<? super BeanPostProcessor> filter) {
			boolean success = super.removeIf(filter);
			if (success) {
				beanPostProcessorCache = null;
			}
			return success;
		}

		@Override
		public void replaceAll(UnaryOperator<BeanPostProcessor> operator) {
			super.replaceAll(operator);
			beanPostProcessorCache = null;
		}
	}


	/**
	 * 预过滤post-processors的内部缓存.
	 *
	 * @since 5.3
	 */
	static class BeanPostProcessorCache {

		final List<InstantiationAwareBeanPostProcessor> instantiationAware = new ArrayList<>();

		final List<SmartInstantiationAwareBeanPostProcessor> smartInstantiationAware = new ArrayList<>();

		final List<DestructionAwareBeanPostProcessor> destructionAware = new ArrayList<>();

		final List<MergedBeanDefinitionPostProcessor> mergedDefinition = new ArrayList<>();
	}

}
