/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.beans.factory.xml;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.lang.Nullable;

/**
 * {@link DefaultBeanDefinitionDocumentReader}用于处理Spring
 * XML配置文件中自定义namespace的基本接口.
 *
 * <p>实现期望返回自定义顶级标记的{@link BeanDefinitionParser}接口的实现和
 * 自定义嵌套标记的{@link BeanDefinitionDecorator}接口的实现.
 *
 * <p>当parser直接在{@code <beans>}标签下遇到自定义标签时, 它将调用
 * {@link #parse}, 当parser直接遇到{@code <bean>}标签下面的自定义标签时
 * 它将调用{@link #decorate}.
 *
 * <p>编写自己的自定义元素扩展的开发人员通常不会直接实现这个接口, 而是使用提供的
 * {@link NamespaceHandlerSupport}类.
 *
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 2.0
 * @see DefaultBeanDefinitionDocumentReader
 * @see NamespaceHandlerResolver
 */
public interface NamespaceHandler {

	/**
	 * 由{@link DefaultBeanDefinitionDocumentReader}在构造之后但在
	 * 解析任何自丁广义元素之前调用.
	 * @see NamespaceHandlerSupport#registerBeanDefinitionParser(String, BeanDefinitionParser)
	 */
	void init();

	/**
	 * 解析指定的{@link Element}, 并向嵌入在提供的{@link ParserContext}中的
	 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
	 * 注册任何产生的{@link BeanDefinition BeanDefinitions}.
	 * <p>如果实现希望在(例如){@code <property>}标签内嵌套使用,
	 * 则应该返回从解析阶段产生的主{@code BeanDefinition}.
	 * <p>如果在嵌套场景中<strong>不</strong>使用实现, 则可能返回{@code null}.
	 * @param element 要被解析为一个或多个{@code BeanDefinitions}的元素
	 * @param parserContext 封装解析过程当前状态的对象
	 * @return the primary {@code BeanDefinition} (can be {@code null} as explained above)
	 */
	@Nullable
	BeanDefinition parse(Element element, ParserContext parserContext);

	/**
	 * 解析指定的{@link Node}并修饰提供的{@link BeanDefinitionHolder},
	 * 返回修饰后的定义.
	 * <p>The {@link Node}可以是{@link org.w3c.dom.Attr}或{@link Element},
	 * 这取决于是否解析自定义属性或元素.
	 * <p>实现可以选择返回一个全新的定义, 它将替换生成的
	 * {@link org.springframework.beans.factory.BeanFactory}中的原始定义.
	 * <p>提供的{@link ParserContext}可用于注册支持主定义所需的任何其他bean.
	 * @param source 要解析的source元素或属性
	 * @param definition 当前bean定义
	 * @param parserContext 封装解析过程当前状态的对象
	 * @return 修饰过的定义(要在BeanFactory中注册), 或者如果不需要修饰, 则简单地使用
	 * 原始bean定义. 严格来说, {@code null}值是无效的, 但是会像返回原始bean定义的情况
	 * 一样被宽大处理.
	 */
	@Nullable
	BeanDefinitionHolder decorate(Node source, BeanDefinitionHolder definition, ParserContext parserContext);

}
