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

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.lang.Nullable;

/**
 * 支持需要处理{@link org.springframework.beans.factory.FactoryBean}实例的singleton
 * 注册的基类, 与{@link DefaultSingletonBeanRegistry}的singleton管理集成.
 *
 * <p>作为{@link AbstractBeanFactory}的基类.
 *
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/** 由FactoryBeans创建的singleton对象的缓存: 从FactoryBean到对象的名称. */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);


	/**
	 * 确定给定的FactoryBean的类型.
	 * @param factoryBean 要检查的FactoryBean实例
	 * @return FactoryBean的对象类型, 如果类型尚未确定, 则为{@code null}
	 */
	@Nullable
	protected Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(
						(PrivilegedAction<Class<?>>) factoryBean::getObjectType, getAccessControlContext());
			}
			else {
				return factoryBean.getObjectType();
			}
		}
		catch (Throwable ex) {
			// 从FactoryBean的getObjectType实现抛出.
			logger.info("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * 获取要从给定的FactoryBean公开的对象(如果以缓存形式可用). 快速检查最小同步.
	 * @param beanName bean的名称
	 * @return 从FactoryBean获得的对象, 如果不可用, 则为{@code null}
	 */
	@Nullable
	protected Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * 获取要从给定的FactoryBean公开的对象.
	 * @param factory FactoryBean实例
	 * @param beanName bean的名称
	 * @param shouldPostProcess bean是否进行后处理
	 * @return 从FactoryBean获得的对象
	 * @throws BeanCreationException 如果FactoryBean对象创建失败
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
		// get bean step21: 如果是单例, 并且单例缓存中有这个beanName的实例, 则直接加锁
		if (factory.isSingleton() && containsSingleton(beanName)) {
			synchronized (getSingletonMutex()) {
				Object object = this.factoryBeanObjectCache.get(beanName);
				if (object == null) {
					object = doGetObjectFromFactoryBean(factory, beanName);
					// 如果上面的getObject()调用期间没有将其存储在那里, 则只进行后处理和存储
					// (例如, 因为自定义getBean调用触发了循环引用处理)
					Object alreadyThere = this.factoryBeanObjectCache.get(beanName);
					if (alreadyThere != null) {
						object = alreadyThere;
					}
					else {
						if (shouldPostProcess) {
							// get bean step22: 如果这个bean还处于创建中, 就不调用后处理方法了, 直接返回创建中的实例
							if (isSingletonCurrentlyInCreation(beanName)) {
								// 暂时返回非后处理的对象, 不存储它.
								return object;
							}
							// get bean step23: 如果没有处于创建中就调用前置处理方法(默认实现仅仅将状态标记为状态创建中)
							beforeSingletonCreation(beanName);
							try {
								// get bean step24: 调用从FactoryBean获取对象后的后置处理方法(默认实现啥都不做,
								// 子类实现调用了BeanPostProcessor的postProcessAfterInitialization方法)
								object = postProcessObjectFromFactoryBean(object, beanName);
							}
							catch (Throwable ex) {
								throw new BeanCreationException(beanName,
										"Post-processing of FactoryBean's singleton object failed", ex);
							}
							finally {
								// get bean step25: 调用创建单例后的后置处理方法(默认实现仅仅是从创建中状态移除)
								afterSingletonCreation(beanName);
							}
						}
						if (containsSingleton(beanName)) {
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				return object;
			}
		}
		else {
			Object object = doGetObjectFromFactoryBean(factory, beanName);
			if (shouldPostProcess) {
				try {
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			return object;
		}
	}

	/**
	 * 获取要从给定的FactoryBean公开的对象.
	 * @param factory FactoryBean实例
	 * @param beanName bean的名称
	 * @return 从FactoryBean获得的对象
	 * @throws BeanCreationException 如果FactoryBean对象创建失败
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, String beanName) throws BeanCreationException {
		Object object;
		try {
			if (System.getSecurityManager() != null) {
				AccessControlContext acc = getAccessControlContext();
				try {
					object = AccessController.doPrivileged((PrivilegedExceptionAction<Object>) factory::getObject, acc);
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				object = factory.getObject();
			}
		}
		catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// 不要接受尚未完全初始化的FactoryBean的null值: 许多FactoryBean只返回空值.
		if (object == null) {
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(
						beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			object = new NullBean();
		}
		return object;
	}

	/**
	 * 对从FactoryBean获得的给定对象的后处理.
	 * 结果对象将为bean引用公开.
	 * <p>默认实现只是简单地按原样返回给定对象.
	 * 子类可以重写它, 例如, 应用post-processors.
	 * @param object 从FactoryBean获得的对象.
	 * @param beanName bean的名称
	 * @return 要公开的对象
	 * @throws org.springframework.beans.BeansException 如果任何后处理失败
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * 如果可能, 获取给定bean的FactoryBean.
	 * @param beanName bean的名称
	 * @param beanInstance 对应的bean实例
	 * @return 作为FactoryBean的bean实例
	 * @throws BeansException 如果给定的bean不能作为FactoryBean公开
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return (FactoryBean<?>) beanInstance;
	}

	/**
	 * 重写以清除FactoryBean对象缓存.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanObjectCache.remove(beanName);
		}
	}

	/**
	 * 重写以清除FactoryBean对象缓存.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanObjectCache.clear();
		}
	}

	/**
	 * 返回此bean工厂的security context. 如果设置了security manager,
	 * 则将使用此方法返回的security context的特权执行与用户代码交互.
	 * @see AccessController#getContext()
	 */
	protected AccessControlContext getAccessControlContext() {
		return AccessController.getContext();
	}

}
