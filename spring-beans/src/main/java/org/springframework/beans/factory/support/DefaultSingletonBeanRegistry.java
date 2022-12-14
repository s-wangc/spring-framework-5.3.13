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

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 共享bean实例的通用注册表, 实现
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * 允许注册应该为注册表的所有调用者共享的singleton实例, 这些实例将通过bean名称获得.
 *
 * <p>还支持注册{@link org.springframework.beans.factory.DisposableBean}实例
 * (可能对应也可能不对应注册的singleton), 在关闭注册表时销毁. 可以注册bean之间的依赖关系,
 * 以强制执行适当的关闭顺序.
 *
 * <p>这个类主要用作{@link org.springframework.beans.factory.BeanFactory}实现的基类,
 * 它提供了对singleton bean实例的公共管理. 注意,
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}接口
 * 货站了{@link SingletonBeanRegistry}接口.
 *
 * <p>注意, 这个类既没有bean定义概念, 也没有bean实例的特定创建过程, 这与
 * {@link AbstractBeanFactory}和{@link DefaultListableBeanFactory}(继承自它)不同.
 * 也可以作为要委托的嵌套helper使用.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** 保留的最大抑制异常数. */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/** singleton对象的缓存: bean名到bean实例. */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/** singleton工厂的缓存: bean名称到ObjectFactory. */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/** 早期singleton对象的缓存: bean名称到bean实例. */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/** 已注册的singleton的Set, 包含按注册顺序排列的bean名称. */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/** 当前正在创建的bean的名称. */
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** 当前从创建检查中排除的bean的名称. */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** 抑制异常的集合, 可用于关联相关原因. */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/** 指示当前是否在destroySingletons中的标志. */
	private boolean singletonsCurrentlyInDestruction = false;

	/** 一次性bean实例: 一次性实例的 bean名称. */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/** 包含bean名称之间的映射: bean名称到bean包含的bean名称. */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/** 依赖bean名称之间的映射: bean名称到依赖bean名称的Set. */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/** 依赖bean名称之间的映射: bean名称到被依赖bean名称的Set. */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * 将给定的singleton对象添加到此工厂的singleton缓存.
	 * <p>被称为渴望登记singleton.
	 * @param beanName bean的名称
	 * @param singletonObject singleton对象
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.put(beanName, singletonObject);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * 如果需要, 添加给定的singleton工厂以构建指定的singleton.
	 * <p>需要立即注册singleton, 例如能够解析循环引用.
	 * @param beanName bean的名称
	 * @param singletonFactory singleton对象的工厂
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * 返回在给定名称下注册的(原始)singleton对象.
	 * <p>检查已实例化的singleton, 并允许对当前创建的singleton进行早期引用(解析循环引用).
	 * @param beanName 要查找的bean的名称
	 * @param allowEarlyReference 是否应创建早期引用
	 * @return 已注册的singleton对象, 如果未找到, 则为{@code null}
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		// get bean step04: 检查现有的单例缓存中是否存在现成的单例实例
		// 快速检查现有实例, 无需完整的singleton锁
		Object singletonObject = this.singletonObjects.get(beanName);
		// get bean step05: 如果现有的单例缓存中没有现成的单例实例, 并且这个单例还在创建中, 那我们就把这个创建中的实例哪里出来
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			singletonObject = this.earlySingletonObjects.get(beanName);
			// get bean step06: 如果这个创建中的单例实例其实为null, 并且传入的参数指定要创建早期引用的话, 那我们就给单例缓存Map加一个锁
			if (singletonObject == null && allowEarlyReference) {
				synchronized (this.singletonObjects) {
					// get bean step07: 在别的线程释放了singletonObjects的锁之后再去从单例缓存中取一遍
					// 在完整singleton锁内一致创建早期引用
					singletonObject = this.singletonObjects.get(beanName);
					// get bean step08: 如果得到锁之后去取对象还是取不到, 那就说明我们需要自己动手来创建一个了
					if (singletonObject == null) {
						// get bean step09: 如果从创建中的缓存里面去取还是取不到的话, 我们就看一下单例工厂中有没有我们要的这个bean的工厂
						singletonObject = this.earlySingletonObjects.get(beanName);
						if (singletonObject == null) {
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							// get bean step10: 如果我们在单例工厂缓存中找到了我们要的东西, 那我们就调用工厂的getObject()方法创建一个对象
							if (singletonFactory != null) {
								singletonObject = singletonFactory.getObject();
								// get bean step11: 使用单例工厂获取到对象之后, 就把这个对象放到创建中缓存里面, 然后从单例工厂缓存中删掉, 最后返回这个创建中的实例就好了
								this.earlySingletonObjects.put(beanName, singletonObject);
								this.singletonFactories.remove(beanName);
							}
						}
					}
				}
			}
		}
		return singletonObject;
	}

	/**
	 * 返回在给定名称下注册的(原始)singleton对象, 如果尚未注册, 则创建并注册新的singleton对象.
	 * @param beanName bean的名称
	 * @param singletonFactory 如果需要, 使用ObjectFactory懒惰地创建singleton
	 * @return 已注册的singleton对象
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "Bean name must not be null");
		synchronized (this.singletonObjects) {
			Object singletonObject = this.singletonObjects.get(beanName);
			if (singletonObject == null) {
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				beforeSingletonCreation(beanName);
				boolean newSingleton = false;
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					singletonObject = singletonFactory.getObject();
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// 是否同时隐式出现了singleton对象 -> 如果是则继续, 因为异常指示该状态.
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						throw ex;
					}
				}
				catch (BeanCreationException ex) {
					if (recordSuppressedExceptions) {
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				}
				finally {
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					afterSingletonCreation(beanName);
				}
				if (newSingleton) {
					addSingleton(beanName, singletonObject);
				}
			}
			return singletonObject;
		}
	}

	/**
	 * 注册一个在创建singleton bean实例期间被抑制的异常, 例如临时循环引用解析问题.
	 * <p>默认实现保留该注册中心抑制异常集合中的任何给定异常, 最多不超过100个异常, 并将它们作为
	 * 相关原因添加到最终的顶级{@link BeanCreationException}.
	 * @param ex 要注册的异常
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * 从该工厂的singleton缓存中删除具有给定名称的bean,
	 * 以便能够在创建失败时清除singleton的即时注册.
	 * @param beanName bean的名称
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * 返回指定的singleton bean当前是否正在创建中(在整个工厂内).
	 * @param beanName bean的名称
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * 在创建singleton之前回调.
	 * <p>默认实现将singleton注册为当前正在创建中.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * 创建singleton实例后的回调.
	 * <p>默认实现将singleton标记为不在创建中.
	 * @param beanName 已创建的singleton的名称
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * 将给定的bean添加到该注册中心的disposable bean列表中.
	 * <p>disposable bean通常与已注册的singleton对应, 与bean名称匹配, 但可能是
	 * 不同的实例(例如, singleton的disposable bean适配器没有自然地实现Spring的
	 * disposable bean接口).
	 * @param beanName bean的名称
	 * @param bean bean实例
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * 注册两个bean之间的包含关系, 例如内部bean与其包含的外部bean之间的包含关系.
	 * <p>还将包含的bean注册为在销毁顺序上依赖于被包含的bean.
	 * @param containedBeanName 所包含(内部)bean的名称
	 * @param containingBeanName 包含(外部)bean的名称
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * 为给定的bean注册一个依赖的bean, 在给定的bean被销毁之前销毁它.
	 * @param beanName bean的名称
	 * @param dependentBeanName 依赖bean的名称
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		String canonicalName = canonicalName(beanName);

		synchronized (this.dependentBeanMap) {
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		synchronized (this.dependenciesForBeanMap) {
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * 确定指定的依赖bean是否已注册为依赖于给定bean或其任何传递依赖项.
	 * @param beanName 依赖bean的名称
	 * @param dependentBeanName 依赖bean的名称
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		String canonicalName = canonicalName(beanName);
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		if (dependentBeans == null) {
			return false;
		}
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		for (String transitiveDependency : dependentBeans) {
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			alreadySeen.add(beanName);
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 确定是否已为给定名称注册了依赖bean.
	 * @param beanName 依赖bean的名称
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * 返回依赖于指定bean的所有bean的名称(如果有的话).
	 * @param beanName bean的名称
	 * @return 依赖bean名称的数组, 如果没有则为空数组
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * 返回指定bean依赖的所有bean的名称(如果有的话).
	 * @param beanName bean的名称
	 * @return bean所依赖的bean名称数组, 如果没有则为空数组
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		clearSingletonCache();
	}

	/**
	 * 清除该注册表中所有缓存的singleton实例.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * 销毁给定的bean. 如果找到了相应的disposable bean实例, 则委托给{@code destroyBean}.
	 * @param beanName bean的名称
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// 删除给定名称的注册singleton(如果有的话).
		removeSingleton(beanName);

		// 销毁相应的DisposableBean实例.
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);
	}

	/**
	 * 销毁给定的bean. 必须在bean本身之前销毁依赖于给定bean的bean.
	 * 不应该抛出任何异常.
	 * @param beanName bean的名称
	 * @param bean 要销毁的bean实例
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// 首先触发依赖bean的销毁...
		Set<String> dependencies;
		synchronized (this.dependentBeanMap) {
			// 在完全同步内, 以确保断开连接
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			for (String dependentBeanName : dependencies) {
				destroySingleton(dependentBeanName);
			}
		}

		// 现在就把bean销毁掉...
		if (bean != null) {
			try {
				bean.destroy();
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// 触发包含的bean的销毁...
		Set<String> containedBeans;
		synchronized (this.containedBeanMap) {
			// 在完全同步内, 以确保断开连接
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		// 从其他bean的依赖项中删除已销毁的bean.
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// 删除已销毁bean的准备依赖项信息.
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * 将singleton互斥对象暴露给子类和外部合作者.
	 * <p>如果子类执行任何类型的扩展singleton创建阶段, 他们应该在给定的对象上同步.
	 * 特别是, 子类<i>不</i>应该在singleton创建时使用自己的互斥锁, 以避免lazy-init
	 * 情况下可能出现的死锁.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
