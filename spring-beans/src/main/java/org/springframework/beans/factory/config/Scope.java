/*
 * Copyright 2002-2016 the original author or authors.
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

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.lang.Nullable;

/**
 * {@link ConfigurableBeanFactory}使用的策略接口, 表示保存bean实例的目标scope,
 * 这允许使用为{@link ConfigurableBeanFactory#registerScope(String, Scope) specific key}
 * 注册的自定义scope扩展BeanFactory的标准scope
 * {@link ConfigurableBeanFactory#SCOPE_SINGLETON "singleton"}和
 * {@link ConfigurableBeanFactory#SCOPE_PROTOTYPE "prototype"}.
 *
 * <p>{@link org.springframework.context.ApplicationContext}实现
 * (例如{@link org.springframework.web.context.WebApplicationContext})
 * 可能会根据这个scope SPI注册额外的特定于其环境的标准scope, 例如
 * {@link org.springframework.web.context.WebApplicationContext#SCOPE_REQUEST "request"}
 * 和{@link org.springframework.web.context.WebApplicationContext#SCOPE_SESSION "session"}.
 *
 * <p>即使它的主要用途是用于web环境中的扩展scope, 这个SPI也是完全通用的: 它提供了从任何底层存储机制
 * (如HTTP session或自定义对话机制)获取和放置对象的能力. 传入该类的{@code get}和{@code remove}
 * 方法的名称将标识当前scope中的目标对象.
 *
 * <p>{@code Scope}实现应该是线程安全的. 如果需要的话, 一个{@code Scope}实例可以同时与多个bean
 * 工厂一起使用(除非它显式地想要知道包含的BeanFactory), 任意数量的线程可以从任意数量的工厂并发访问
 * {@code Scope}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 2.0
 * @see ConfigurableBeanFactory#registerScope
 * @see CustomScopeConfigurer
 * @see org.springframework.aop.scope.ScopedProxyFactoryBean
 * @see org.springframework.web.context.request.RequestScope
 * @see org.springframework.web.context.request.SessionScope
 */
public interface Scope {

	/**
	 * 从底层scope返回具有给定名称的的对象, 如果在底层存储机制中没有找到则返回
	 * {@link org.springframework.beans.factory.ObjectFactory#getObject() creating it}.
	 * <p>这是scope的中心操作, 也是唯一绝对需要的操作.
	 * @param name 要检索的对象的名称
	 * @param objectFactory 如果底层存储机制中不存在这个scope的对象, 则使用{@link ObjectFactory}
	 * 创建该scope中的对象
	 * @return 想要的对象(从不为{@code null})
	 * @throws IllegalStateException 如果底层scope当前不是活动的
	 */
	Object get(String name, ObjectFactory<?> objectFactory);

	/**
	 * 从底层scope中移除具有给定{@code name}的对象.
	 * <p>如果没有找到对象, 返回{@code null}; 否则返回移除的{@code Object}.
	 * <p>注意, 实现还应该删除指定对象的注册销毁回调(如果有的话). 但是, 在这种情况下,
	 * <i>不</i>需要<i>执行</i>一个注册的销毁回调, 因为对象将被调用者销毁(如果合适).
	 * <p><b>注意: 这是一个可选操作.</b> 不过实现不支持显式删除对象, 则可能抛出
	 * {@link UnsupportedOperationException}.
	 * @param name 要删除的对象的名称
	 * @return 被移除的对象, 如果没有对象, 则为{@code null}
	 * @throws IllegalStateException 如果底层scope当前不是活动的
	 * @see #registerDestructionCallback
	 */
	@Nullable
	Object remove(String name);

	/**
	 * 注册一个回调, 以便在scope内指定对象被销毁时执行(或者在整个scope被销毁时执行,
	 * 如果scope不销毁单个对象而只是完整地终止).
	 * <p><b>注意: 这是一个可选操作.</b> 此方法只会被用于具有实际销毁配置
	 * (DisposableBean, destroy-method, DestructionAwareBeanPostProcessor)
	 * 的scope bean调用. 实现应该尽其所能在适当的时间执行给定的回调. 如果底层运行时环境
	 * 根本不支持这样的回调, 则<i>必须忽略该回调, 并记录相应的警告.
	 * <p>注意, 'destruction'指的是对象组我诶scope自身生命周期的一部分自动销毁, 而不是
	 * 应用程序显式删除的单个scope对象. 如果通过这个外观的{@link #remove(String)}方法
	 * 删除了一个scope对象, 那么任何注册的销毁回调也应该被删除, 假设被删除的对象将被重用或
	 * 手动销毁.
	 * @param name 要执行销毁回调的对象的名称
	 * @param callback 要执行的销毁回调.
	 * 注意, 传入的Runnable永远不会抛出异常, 因此它可能完全地执行, 而不需要包含一个try-catch
	 * 块. 此外, Runnable通常是可序列化的, 前提是它的目标对象也是可序列化的.
	 * @throws IllegalStateException 如果底层scope当前不是活动的
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see org.springframework.beans.factory.support.AbstractBeanDefinition#getDestroyMethodName()
	 * @see DestructionAwareBeanPostProcessor
	 */
	void registerDestructionCallback(String name, Runnable callback);

	/**
	 * 解析给定key的context对象(如果有的话).
	 * 例如, key "request"的HttpServletRequest对象.
	 * @param key contextual key
	 * @return 对应的对象, 如果没有找到则返回{@code null}
	 * @throws IllegalStateException 如果底层scope当前不是活动的
	 */
	@Nullable
	Object resolveContextualObject(String key);

	/**
	 * 返回当前底层scope的<em>conversation ID</em>(如果有的话).
	 * <p>conversation ID的确切含义取决于底层存储机制. 在session-scoped对象的情况下,
	 * conversation ID通常等于(或从)
	 * {@link javax.servlet.http.HttpSession#getId() session ID};
	 * 对于位于整个session中的自定义conversation, 当前conversation的特定ID是合适的.
	 * <p><b>注意: 这是一个可选操作.</b> 如果底层存储机制对这样的ID没有明显的候选, 那么
	 * 在该方法的实现中返回{@code null}是完全有效的.
	 * @return conversation ID, 如果当前scope没有conversation ID, 则为{@code null}
	 * @throws IllegalStateException 如果底层scope当前不是活动的
	 */
	@Nullable
	String getConversationId();

}
