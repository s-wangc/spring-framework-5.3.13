/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.beans.factory.config;

import org.springframework.lang.Nullable;

/**
 * 为共享bean实例定义注册表的接口.
 * 可以由{@link org.springframework.beans.factory.BeanFactory}实现来实现,
 * 以便以统一的方式公开它们的singleton管理设施.
 *
 * <p>{@link ConfigurableBeanFactory}接口扩展了该接口.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see ConfigurableBeanFactory
 * @see org.springframework.beans.factory.support.DefaultSingletonBeanRegistry
 * @see org.springframework.beans.factory.support.AbstractBeanFactory
 */
public interface SingletonBeanRegistry {

	/**
	 * 将给定的现有对象注册为bean注册表中给定bean名称下的singleton.
	 * <p>给定实例应完全初始化; 注册表不会执行任何初始化回调(特别是, 它不会调用InitializingBean
	 * 的{@code afterPropertiesSet}方法). 给定实例也不会接收任何destroy回调
	 * (如DisposableBean的{@code destroy}方法).
	 * <p>在完整BeanFactory中运行时: <b>如果您的bean应该接收初始化and/ordestroy回调,
	 * 则注册bean定义而不是现有实例.</b>
	 * <p>通常在注册表配置期间, 但也可用于运行时注册singleton. 因此, 注册表实现应synchronize
	 * singleton访问; 如果它支持BeanFactory的singleton lazy初始化, 它无论如何都必须这样做.
	 * @param beanName bean的名称
	 * @param singletonObject 现有的singleton对象
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.DisposableBean#destroy
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry#registerBeanDefinition
	 */
	void registerSingleton(String beanName, Object singletonObject);

	/**
	 * 返回在给定名称下注册的(原始)singleton对象.
	 * <p>仅检查已实例化的singleton; 对于尚未实例化的singleton bean定义, 不返回对象.
	 * <p>该方法的主要目的是访问手动注册的singleton(见{@link #registerSingleton}).
	 * 还可以用于访问已创建的bean定义所定义的singleton.
	 * <p><b>注意:</b> 此查找方法不知道FactoryBean前缀或别名.
	 * 在获取singleton实例之前, 需要首先解析规范bean名称.
	 * @param beanName 要查找的bean的名称
	 * @return 已注册的singleton对象, 如果未找到, 则为{@code null}
	 * @see ConfigurableListableBeanFactory#getBeanDefinition
	 */
	@Nullable
	Object getSingleton(String beanName);

	/**
	 * 检查此注册表是否包含具有给定名称的singleton实例.
	 * <p>仅检查已实例化的singleton; 对于尚未实例化的singleton bean, 不返回{@code true}.
	 * <p>该方法的主要目的是检查手动注册的singleton(见{@link #registerSingleton}).
	 * 还可以用于检查bean definition定义的singleton是否已经创建.
	 * <p>要检查bean工厂是否包含具有给定名称的bean定义, 请使用ListableBeanFactory的
	 * {@code containsBeanDefinition}. 调用{@code containsBeanDefinition}
	 * 和{@code containsSingleton}回答特定的bean工厂是否包含具有给定名称的本地bean实例.
	 * <p>使用BeanFactory的{@code containsBean}进行一般检查, 检查工厂是否知道具有给定名称bean
	 * (无论是手动注册的singleton还是由bean定义创建的), 还检查祖先工厂.
	 * <p><b>注意:</b> 此查找方法不知道FactoryBean前缀或别名.
	 * 在检查singleton状态之前, 您需要首先解析规范bean名称.
	 * @param beanName 要查找的bean的名称
	 * @return 如果此bean工厂包含具有给定名称的singleton实例
	 * @see #registerSingleton
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 * @see org.springframework.beans.factory.BeanFactory#containsBean
	 */
	boolean containsSingleton(String beanName);

	/**
	 * 返回在此注册中心注册的singletonbean的名称.
	 * <p>仅检查已实例化的singleton; 不返回尚未实例化的singleton bean定义的名称.
	 * <p>该方法的主要目的是检查手动注册的singleton(见{@link #registerSingleton}).
	 * 还可以用于检查bean definition定义的哪些singleton已经创建.
	 * @return 作为字符串数组的名称list(从不为{@code null})
	 * @see #registerSingleton
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry#getBeanDefinitionNames
	 * @see org.springframework.beans.factory.ListableBeanFactory#getBeanDefinitionNames
	 */
	String[] getSingletonNames();

	/**
	 * 返回在此注册中心注册的singleton bean的数量.
	 * <p>仅检查已实例化的singleton; 不计算尚未实例化的singleton bean定义.
	 * <p>该方法的主要目的是检查手动注册的singleton(见{@link #registerSingleton}).
	 * 还可以用来计算已经创建的bean definition定义的singleton数.
	 * @return singleton bean的数量
	 * @see #registerSingleton
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry#getBeanDefinitionCount
	 * @see org.springframework.beans.factory.ListableBeanFactory#getBeanDefinitionCount
	 */
	int getSingletonCount();

	/**
	 * 返回此注册表中使用的singleton mutex对象(用于外部合作者).
	 * @return mutex对象(从不为{@code null})
	 * @since 4.2
	 */
	Object getSingletonMutex();

}
