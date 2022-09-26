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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link BeanDefinitionDocumentReader}接口的默认实现, 该接口根据"spring-beans"
 * DTD和XSD(Spring默认的bean定义格式)格式读取bean定义.
 *
 * <p>所需XML文档的结构、elements和attribute names都硬编码在这个类中. (当然, 如果需要
 * 产生这种格式, 可以运行转换). {@code <beans>}不需要是XML文档的根元素: 这个类将解析XML
 * 文件中的所有bean定义元素, 而不管实际的根元素是什么.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String NESTED_BEANS_ELEMENT = "beans";

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private XmlReaderContext readerContext;

	@Nullable
	private BeanDefinitionParserDelegate delegate;


	/**
	 * 该实现根据"spring-beans"XSD(或历史上的DTD)解析bean定义.
	 * <p>打开一个DOM文档; 然后初始化{@code <beans/>}级别的默认设置;
	 * 然后解析包含的bean定义.
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		this.readerContext = readerContext;
		doRegisterBeanDefinitions(doc.getDocumentElement());
	}

	/**
	 * 返回此parser处理的XML资源的描述符.
	 */
	protected final XmlReaderContext getReaderContext() {
		Assert.state(this.readerContext != null, "No XmlReaderContext available");
		return this.readerContext;
	}

	/**
	 * 调用{@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * 以从提供的{@link Element}中提取源metadata.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}


	/**
	 * 在给定的根{@code <beans/>}元素中注册每个bean定义.
	 */
	@SuppressWarnings("deprecation")  // for Environment.acceptsProfiles(String...)
	protected void doRegisterBeanDefinitions(Element root) {
		// xml parse step01: 将上下文中的代理缓存起来
		// 任何嵌套的<beans>元素都会导致该方法的递归.
		// 为了正确地传播和保存<beans> default-*属性, 请跟踪当前(parent)代理, 该代理可能为空.
		// 创建带有对父代理引用的新(子)代理以实现回退, 然后最终将新的(child)代理, 并带有对父代理
		// 的引用, 以实现回退, 然后最终将this.delegate重置为其原始(父)引用.
		// 此行为模拟了一堆代理, 但实际上并不需要代理.
		BeanDefinitionParserDelegate parent = this.delegate;
		this.delegate = createDelegate(getReaderContext(), root, parent);

		// xml parse step04: 如果当前标签的命名空间时默认命名空间, 先读取profile值(因为这是xml文件, profile的配置文件表达式在xml中不受支持,
		// 因为里面存在&这些符号), 只有手动读取profile了
		if (this.delegate.isDefaultNamespace(root)) {
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
						profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
				// 我们不能使用Profiles.of(...), 因为配置文件表达式在XML配置中不受支持.
				// 详情参见SPR-12458.
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Skipped XML bean definition file due to specified profiles [" + profileSpec +
								"] not matching: " + getReaderContext().getResource());
					}
					return;
				}
			}
		}

		// xml parse step05: 进行xml处理的前置操作
		preProcessXml(root);
		// xml parse step06: 开始解析bean定义
		parseBeanDefinitions(root, this.delegate);
		// xml parse step37: 进行xml处理的后置操作
		postProcessXml(root);

		// xml parse step38: 把代理放回原位, 恢复现场
		this.delegate = parent;
	}

	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {

		// xml parse step02: 使用原来的上下文创建一个新的代理(保证上下文不会随着代理的改变而改变)
		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		// xml parse step03: 从父代理和root标签获取当前代理的初始化值(有限使用当前root标签的值, 如果当前root标签的值为默认值则看父代理, 如果没有父代理则为默认值)
		// xml读取有一个细节: 只要xsd文件中定义了默认值, 即使标签中实际上没有写值, 调用获取属性的方法时也能获取到一个值(默认值)
		delegate.initDefaults(root, parentDelegate);
		return delegate;
	}

	/**
	 * 解析文档中root级别的元素:
	 * "import", "alias", "bean".
	 * @param root 文档的DOM root元素
	 */
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		// xml parse step07: 首先判断一下root元素是不是默认命名空间, 如果不是默认命名空间就直接走解析自定义元素的流程
		if (delegate.isDefaultNamespace(root)) {
			NodeList nl = root.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				// xml parse step08: 如果root标签时默认命名空间, 则循环遍历其子元素; 如果子元素是默认命名空间, 则解析默认命名空间, 否则解析自定义元素
				if (node instanceof Element) {
					Element ele = (Element) node;
					if (delegate.isDefaultNamespace(ele)) {
						parseDefaultElement(ele, delegate);
					}
					else {
						delegate.parseCustomElement(ele);
					}
				}
			}
		}
		else {
			delegate.parseCustomElement(root);
		}
	}

	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		// xml parse step09: 解析import标签
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			importBeanDefinitionResource(ele);
		}
		// xml parse step13: 解析alias标签(解析alias标签中的name和alias属性, 只要这两个属性都不为空, 就把别名注册到工厂中)
		else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			processAliasRegistration(ele);
		}
		// xml parse step14: 解析bean标签
		else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			processBeanDefinition(ele, delegate);
		}
		// xml parse step36: 解析beans标签, 其实这就是递归了
		else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// recurse
			doRegisterBeanDefinitions(ele);
		}
	}

	/**
	 * 解析一个"import"元素, 并将bean定义从给定的资源加载到bean工厂中.
	 */
	protected void importBeanDefinitionResource(Element ele) {
		// xml parse step10: 如果import元素的resource元素值为空, 则return
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}

		// xml parse step11: 使用environment中的值替换resource中的placeholder
		// 解析系统属性: e.g. "${user.dir}"
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

		Set<Resource> actualResources = new LinkedHashSet<>(4);

		// xml parse step12: 先判断一下resource是绝对URI还是相对URI; 不管是绝对还是相对, 最后都调用reader的loadBeanDefinitions来加载bean定义
		// 加载bean定义的时候还传入了一个Set记录我们到底加载了多少个xml资源
		// 发现该位置是绝对URI还是相对URI, 避免单条import路径的循环加载
		// 意思就是说这个操作只是为了防止循环加载, 不是为了防止重复加载
		boolean absoluteLocation = false;
		try {
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		}
		catch (URISyntaxException ex) {
			// 不能转换为URI, 考虑到相对位置, 除非它是众所周知的Spring前缀"classpath*:"
		}

		// 绝对的还是相对的?
		if (absoluteLocation) {
			try {
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		}
		else {
			// 没有URL -> 考虑到resource location相对于当前位置.
			try {
				int importCount;
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				if (relativeResource.exists()) {
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					actualResources.add(relativeResource);
				}
				else {
					String baseLocation = getReaderContext().getResource().getURL().toString();
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			}
			catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from relative location [" + location + "]", ele, ex);
			}
		}
		Resource[] actResArray = actualResources.toArray(new Resource[0]);
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * 处理给定的alias元素, 向registry注册alias.
	 */
	protected void processAliasRegistration(Element ele) {
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		if (valid) {
			try {
				getReaderContext().getRegistry().registerAlias(name, alias);
			}
			catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**
	 * 处理给定的bean元素, 解析bean定义并将其注册到registry.
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// xml parse step15: 解析bean标签得到一个BeanDefinitionHolder
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
		if (bdHolder != null) {
			// xml parse step32: BeanDefinitionHolder创建好了之后就开始装饰这个bean
			// (根据逻辑来看, 只有bean标签才会进行装饰, 自定义标签时不会进行装饰的)
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
			try {
				// xml parse step35: 把经过装饰的BeanDefinitionHolder注册到工厂中
				// 注册到最后一个装饰实例.
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to register bean definition with name '" +
						bdHolder.getBeanName() + "'", ele, ex);
			}
			// 发送注册事件.
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}
	}


	/**
	 * 在开始处理bean定义之前, 先处理任何定制元素类型, 从而允许XML可扩展.
	 * 该方法是任何其他定制的XML预处理的自然扩展点.
	 * <p>默认实现为空. 例如, 子类可以重写此方法以将自定义元素转换为标准Spring bean定义.
	 * 实现着可以通过相应的accessors访问parser的bean定义reader和底层XML资源.
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * 在我们处理完bean定义之后, 通过最后处理任何定制元素类型, 允许XML可扩展.
	 * 该方法是任何其他定制的XML后处理的自然扩展点.
	 * <p>默认实现为空. 例如, 子类可以重写此方法以将自定义元素转换为标准Spring bean定义.
	 * 实现着可以通过相应的accessors访问parser的bean定义reader和底层XML资源.
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
