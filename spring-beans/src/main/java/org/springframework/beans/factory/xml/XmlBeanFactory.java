/*
 * Copyright 2002-2018 the original author or authors.
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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.Resource;

/**
 * 方便的{@link DefaultListableBeanFactory}扩展, 从XML文档读取bean定义.
 * 代理到下面的{@link XmlBeanDefinitionReader}; 等效于使用带有DefaultListableBeanFactory
 * 的XmlBeanDefinitionReader.
 *
 * <p>所需XML文档的结构、元素和属性名称都硬编码到这个类中. (当然, 如果需要产生这种格式, 可以运行
 * 转换). "beans"不需要是XML文档的根元素: 这个类将解析XML文件中的所有bean定义元素.
 *
 * <p>该类使用{@link DefaultListableBeanFactory}超类注册每个bean定义, 并依赖后者的{@link BeanFactory}
 * 接口实现. 它支持singletons、prototypes和对这两种bean的任意一种的引用. 有关选项和配置风格的详细信息, 请参见
 * {@code "spring-beans-3.x.xsd"} (或者历史上的{@code "spring-beans-2.0.dtd"}).
 *
 * <p><b>对于高级需求, 考虑使用{@link DefaultListableBeanFactory}和{@link XmlBeanDefinitionReader}.</b>
 * 后者允许从多个XML资源读取数据, 并且在实际的XML解析行为方面具有高度的可配置性.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 15 April 2001
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 * @see XmlBeanDefinitionReader
 * @deprecated as of Spring 3.1 in favor of {@link DefaultListableBeanFactory} and
 * {@link XmlBeanDefinitionReader}
 */
@Deprecated
@SuppressWarnings({"serial", "all"})
public class XmlBeanFactory extends DefaultListableBeanFactory {

	private final XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(this);


	/**
	 * 使用给定的资源创建一个新的XmlBeanFactory, 该资源必须可以使用DOM解析.
	 * @param resource 用于加载bean定义的XML资源
	 * @throws BeansException 在加载或解析错误的情况下
	 */
	public XmlBeanFactory(Resource resource) throws BeansException {
		this(resource, null);
	}

	/**
	 * 使用给定的输入流创建一个新的XmlBeanFactory, 该输入流必须可以使用DOM解析.
	 * @param resource 用于加载bean定义的XML资源
	 * @param parentBeanFactory parent bean factory
	 * @throws BeansException 在加载或解析错误的情况下
	 */
	public XmlBeanFactory(Resource resource, BeanFactory parentBeanFactory) throws BeansException {
		super(parentBeanFactory);
		// xml read step01: 调用XmlBeanDefinitionReader.loadBeanDefinitions方法
		this.reader.loadBeanDefinitions(resource);
	}

}
