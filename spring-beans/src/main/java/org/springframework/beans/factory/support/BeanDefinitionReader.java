/*
 * Copyright 2002-2017 the original author or authors.
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

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;

/**
 * bean定义reader的简单接口.
 * 指定带有Resource和String位置参数的load方法.
 *
 * <p>具体的bean定义reader当然可以为bean定义添加额外的load和register方法,
 * 这些方法特定于它们的bean定义格式.
 *
 * <p>请注意, bean定义reader不必实现此接口. 它仅为希望遵循标准命名约定的bean定义reader提供建议.
 *
 * @author Juergen Hoeller
 * @since 1.1
 * @see org.springframework.core.io.Resource
 */
public interface BeanDefinitionReader {

	/**
	 * 返回用于注册bean定义的bean工厂.
	 * <p>工厂通过BeanDefinitionRegistry接口公开, 封装了与bean定义处理相关的方法.
	 */
	BeanDefinitionRegistry getRegistry();

	/**
	 * 返回用于resource locations的resource loader.
	 * 可以检查<b>ResourcePatternResolver</b>接口并进行相应的强制转换,
	 * 以便为给定的resource pattern加载多个resource.
	 * <p>{@code null}返回值表明此bean定义reader不能进行绝对resource加载.
	 * <p>这主要用于从bean定义resource中导入更多的resource, 例如通过XML bean定义中的
	 * "import"tag. 但是, 建议相对于定义resource应用这样的导入; 只有显式的完全resource
	 * locations才会触发绝对resource加载.
	 * <p>还有一种可用的{@code loadBeanDefinitions(String)}方法, 用于从resource
	 * location (或location pattern)加载bean定义. 这可以方便地避免显式的ResourceLoader处理.
	 * @see #loadBeanDefinitions(String)
	 * @see org.springframework.core.io.support.ResourcePatternResolver
	 */
	@Nullable
	ResourceLoader getResourceLoader();

	/**
	 * 返回用于bean类的类加载器.
	 * <p>{@code null}建议不要急于加载bean类, 而只是用类名注册bean定义,
	 * 相应的类稍后(或从不)解析.
	 */
	@Nullable
	ClassLoader getBeanClassLoader();

	/**
	 * 返回用于匿名bean(不指定显式bean名称)的BeanNameGenerator.
	 */
	BeanNameGenerator getBeanNameGenerator();


	/**
	 * 从指定的resource加载bean定义.
	 * @param resource the resource descriptor
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	int loadBeanDefinitions(Resource resource) throws BeanDefinitionStoreException;

	/**
	 * 从指定的resources加载bean定义.
	 * @param resources the resource descriptors
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	int loadBeanDefinitions(Resource... resources) throws BeanDefinitionStoreException;

	/**
	 * 从指定的resource location加载bean定义.
	 * <p>这个location也可以是一个location pattern, 前提是这个bean定义reader的ResourceLoader
	 * 是一个ResourcePatternResolver.
	 * @param location resource location, 要用这个bean定义reader的ResourceLoader
	 * (或ResourcePatternResolver)加载
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 * @see #getResourceLoader()
	 * @see #loadBeanDefinitions(org.springframework.core.io.Resource)
	 * @see #loadBeanDefinitions(org.springframework.core.io.Resource[])
	 */
	int loadBeanDefinitions(String location) throws BeanDefinitionStoreException;

	/**
	 * 从指定的resource locations加载bean定义.
	 * @param locations 要用这个bean定义reader的ResourceLoader
	 * (或ResourcePatternResolver)加载的resource locations
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	int loadBeanDefinitions(String... locations) throws BeanDefinitionStoreException;

}
