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

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.parsing.EmptyReaderEventListener;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.NullSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.ReaderEventListener;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Constants;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.xml.SimpleSaxErrorHandler;
import org.springframework.util.xml.XmlValidationModeDetector;

/**
 * 用于XML bean定义的Bean定义reader.
 * 将实际的XML文档读取委托给{@link BeanDefinitionDocumentReader}接口的实现.
 *
 * <p>通常用于{@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
 * 或{@link org.springframework.context.support.GenericApplicationContext}.
 *
 * <p>这个类加载一个DOM文档, 并对其应用BeanDefinitionDocumentReader.
 * 文档reader将向给定bean工厂注册每个bean定义, 并与后者的
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}接口实现进行通信.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Chris Beams
 * @since 26.11.2003
 * @see #setDocumentReaderClass
 * @see BeanDefinitionDocumentReader
 * @see DefaultBeanDefinitionDocumentReader
 * @see BeanDefinitionRegistry
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 * @see org.springframework.context.support.GenericApplicationContext
 */
public class XmlBeanDefinitionReader extends AbstractBeanDefinitionReader {

	/**
	 * 指示应禁用验证.
	 */
	public static final int VALIDATION_NONE = XmlValidationModeDetector.VALIDATION_NONE;

	/**
	 * 指示应自动检测验证模式.
	 */
	public static final int VALIDATION_AUTO = XmlValidationModeDetector.VALIDATION_AUTO;

	/**
	 * 指示应该使用DTD验证.
	 */
	public static final int VALIDATION_DTD = XmlValidationModeDetector.VALIDATION_DTD;

	/**
	 * 指示应该使用XSD验证.
	 */
	public static final int VALIDATION_XSD = XmlValidationModeDetector.VALIDATION_XSD;


	/** 该类的常量实例. */
	private static final Constants constants = new Constants(XmlBeanDefinitionReader.class);

	private int validationMode = VALIDATION_AUTO;

	private boolean namespaceAware = false;

	private Class<? extends BeanDefinitionDocumentReader> documentReaderClass =
			DefaultBeanDefinitionDocumentReader.class;

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	private ReaderEventListener eventListener = new EmptyReaderEventListener();

	private SourceExtractor sourceExtractor = new NullSourceExtractor();

	@Nullable
	private NamespaceHandlerResolver namespaceHandlerResolver;

	private DocumentLoader documentLoader = new DefaultDocumentLoader();

	@Nullable
	private EntityResolver entityResolver;

	private ErrorHandler errorHandler = new SimpleSaxErrorHandler(logger);

	private final XmlValidationModeDetector validationModeDetector = new XmlValidationModeDetector();

	private final ThreadLocal<Set<EncodedResource>> resourcesCurrentlyBeingLoaded =
			new NamedThreadLocal<Set<EncodedResource>>("XML bean definition resources currently being loaded"){
				@Override
				protected Set<EncodedResource> initialValue() {
					return new HashSet<>(4);
				}
			};


	/**
	 * 为给定的bean工厂创建新的XmlBeanDefinitionReader.
	 * @param registry 以BeanDefinitionRegistry的形式将bean定义加载到BeanFactory中
	 */
	public XmlBeanDefinitionReader(BeanDefinitionRegistry registry) {
		super(registry);
	}


	/**
	 * 设置是否使用XML验证. 默认值是{@code true}.
	 * <p>如果关闭验证. 这个方法会打开namespace感知, 以便在这种场景中仍然正确地处理schema namespaces.
	 * @see #setValidationMode
	 * @see #setNamespaceAware
	 */
	public void setValidating(boolean validating) {
		this.validationMode = (validating ? VALIDATION_AUTO : VALIDATION_NONE);
		this.namespaceAware = !validating;
	}

	/**
	 * 将验证模式设置为按名称使用. 默认值是{@link #VALIDATION_AUTO}.
	 * @see #setValidationMode
	 */
	public void setValidationModeName(String validationModeName) {
		setValidationMode(constants.asNumber(validationModeName).intValue());
	}

	/**
	 * 设置要使用的验证模式. 默认值是{@link #VALIDATION_AUTO}.
	 * <p>注意, 这只激活或禁用验证本身.
	 * 如果要关闭schema文件的验证, 可能需要显式地激活schema namespace支持:
	 * 参见 {@link #setNamespaceAware}.
	 */
	public void setValidationMode(int validationMode) {
		this.validationMode = validationMode;
	}

	/**
	 * 返回要使用的验证模式.
	 */
	public int getValidationMode() {
		return this.validationMode;
	}

	/**
	 * 设置XML解析器是否已应该支持XML namespace.
	 * 默认值是"false".
	 * <p>当验证模式处于active状态时, 通常不需要这样做.
	 * 但是, 如果没有验证, 就必须将其转换为"true", 以便正确地处理schema namespaces.
	 */
	public void setNamespaceAware(boolean namespaceAware) {
		this.namespaceAware = namespaceAware;
	}

	/**
	 * 返回XML parser是否应该支持XML namespace.
	 */
	public boolean isNamespaceAware() {
		return this.namespaceAware;
	}

	/**
	 * 指定要使用的{@link org.springframework.beans.factory.parsing.ProblemReporter}.
	 * <p>默认实现是{@link org.springframework.beans.factory.parsing.FailFastProblemReporter},
	 * 它表现出快速失败行为. 外部工具可以提供另一种实现, 它可以整理错误和警告, 以便在工具UI中显示.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * 指定要使用的{@link ReaderEventListener}.
	 * <p>默认实现是EmptyReaderEventListener, 它丢弃每个事件通知.
	 * 外部工具可以提供另一种实现来监视正在BeanFactory中注册的组件.
	 */
	public void setEventListener(@Nullable ReaderEventListener eventListener) {
		this.eventListener = (eventListener != null ? eventListener : new EmptyReaderEventListener());
	}

	/**
	 * 指定要使用的{@link SourceExtractor}.
	 * <p>默认实现是{@link NullSourceExtractor}, 它只返回{@code null}作为source object.
	 * 这意味着 - 在正常的运行时执行期间 - 没有额外的源元数据附加到bean配置元数据.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new NullSourceExtractor());
	}

	/**
	 * 指定要使用的{@link NamespaceHandlerResolver}.
	 * <p>如果不指定, 默认实例将通过{@link #createDefaultNamespaceHandlerResolver()}创建.
	 */
	public void setNamespaceHandlerResolver(@Nullable NamespaceHandlerResolver namespaceHandlerResolver) {
		this.namespaceHandlerResolver = namespaceHandlerResolver;
	}

	/**
	 * 指定要使用的{@link DocumentLoader}.
	 * <p>默认实现是{@link DefaultDocumentLoader}, 它使用JAXP加载{@link Document}实例.
	 */
	public void setDocumentLoader(@Nullable DocumentLoader documentLoader) {
		this.documentLoader = (documentLoader != null ? documentLoader : new DefaultDocumentLoader());
	}

	/**
	 * 设置用于解析的SAX实体resolver.
	 * <p>缺省值为{@link ResourceEntityResolver}.
	 * 可以为自定义实体解析(例如相对于某些特定的base path)重写.
	 */
	public void setEntityResolver(@Nullable EntityResolver entityResolver) {
		this.entityResolver = entityResolver;
	}

	/**
	 * 返回要使用的EntityResolver to use, 如果没有指定则构建一个默认的resolver.
	 */
	protected EntityResolver getEntityResolver() {
		if (this.entityResolver == null) {
			// 确定要使用的默认EntityResolver.
			ResourceLoader resourceLoader = getResourceLoader();
			if (resourceLoader != null) {
				this.entityResolver = new ResourceEntityResolver(resourceLoader);
			}
			else {
				this.entityResolver = new DelegatingEntityResolver(getBeanClassLoader());
			}
		}
		return this.entityResolver;
	}

	/**
	 * 设置{@code org.xml.sax.ErrorHandler}接口的实现, 用于自定义XML解析errors
	 * 和warnings的处理.
	 * <p>如果不设置, 将使用默认的SimpleSaxErrorHandler, 它只是使用view类的logger
	 * 实例记录warnings, 并重新抛出errors以停止XML转换.
	 * @see SimpleSaxErrorHandler
	 */
	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * 指定要使用的{@link BeanDefinitionDocumentReader}实现, 负责实际读取XML bean定义文档.
	 * <p>默认值是{@link DefaultBeanDefinitionDocumentReader}.
	 * @param documentReaderClass 所需的BeanDefinitionDocumentReader实现类
	 */
	public void setDocumentReaderClass(Class<? extends BeanDefinitionDocumentReader> documentReaderClass) {
		this.documentReaderClass = documentReaderClass;
	}


	/**
	 * 从指定的XML文件加载bean定义.
	 * @param resource XML文件的资源描述符
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	@Override
	public int loadBeanDefinitions(Resource resource) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(new EncodedResource(resource));
	}

	/**
	 * 从指定的XML文件加载bean定义.
	 * @param encodedResource XML文件的资源描述符, 允许指定用于解析文件的编码
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	public int loadBeanDefinitions(EncodedResource encodedResource) throws BeanDefinitionStoreException {
		Assert.notNull(encodedResource, "EncodedResource must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("Loading XML bean definitions from " + encodedResource);
		}

		Set<EncodedResource> currentResources = this.resourcesCurrentlyBeingLoaded.get();

		if (!currentResources.add(encodedResource)) {
			throw new BeanDefinitionStoreException(
					"Detected cyclic loading of " + encodedResource + " - check your import definitions!");
		}

		try (InputStream inputStream = encodedResource.getResource().getInputStream()) {
			InputSource inputSource = new InputSource(inputStream);
			if (encodedResource.getEncoding() != null) {
				inputSource.setEncoding(encodedResource.getEncoding());
			}
			return doLoadBeanDefinitions(inputSource, encodedResource.getResource());
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"IOException parsing XML document from " + encodedResource.getResource(), ex);
		}
		finally {
			currentResources.remove(encodedResource);
			if (currentResources.isEmpty()) {
				this.resourcesCurrentlyBeingLoaded.remove();
			}
		}
	}

	/**
	 * 从指定的XML文件加载bean定义.
	 * @param inputSource 要从中读取的SAX InputSource
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	public int loadBeanDefinitions(InputSource inputSource) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(inputSource, "resource loaded through SAX InputSource");
	}

	/**
	 * 从指定的XML文件加载bean定义.
	 * @param inputSource 要从中读取的SAX InputSource
	 * @param resourceDescription 资源都描述符(可以是{@code null}或空)
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 */
	public int loadBeanDefinitions(InputSource inputSource, @Nullable String resourceDescription)
			throws BeanDefinitionStoreException {

		return doLoadBeanDefinitions(inputSource, new DescriptiveResource(resourceDescription));
	}


	/**
	 * 实际地从指定的XML文件加载bean定义.
	 * @param inputSource 要从中读取的SAX InputSource
	 * @param resource XML文件的资源描述符
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 在加载或解析错误的情况下
	 * @see #doLoadDocument
	 * @see #registerBeanDefinitions
	 */
	protected int doLoadBeanDefinitions(InputSource inputSource, Resource resource)
			throws BeanDefinitionStoreException {

		try {
			Document doc = doLoadDocument(inputSource, resource);
			int count = registerBeanDefinitions(doc, resource);
			if (logger.isDebugEnabled()) {
				logger.debug("Loaded " + count + " bean definitions from " + resource);
			}
			return count;
		}
		catch (BeanDefinitionStoreException ex) {
			throw ex;
		}
		catch (SAXParseException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"Line " + ex.getLineNumber() + " in XML document from " + resource + " is invalid", ex);
		}
		catch (SAXException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"XML document from " + resource + " is invalid", ex);
		}
		catch (ParserConfigurationException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Parser configuration exception parsing XML from " + resource, ex);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"IOException parsing XML document from " + resource, ex);
		}
		catch (Throwable ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Unexpected exception parsing XML document from " + resource, ex);
		}
	}

	/**
	 * 使用配置好的DocumentLoader加载指定的文档.
	 * @param inputSource 要从中读取的SAX InputSource
	 * @param resource XML文件的资源描述符
	 * @return the DOM Document
	 * @throws Exception 当从DocumentLoader抛出时
	 * @see #setDocumentLoader
	 * @see DocumentLoader#loadDocument
	 */
	protected Document doLoadDocument(InputSource inputSource, Resource resource) throws Exception {
		return this.documentLoader.loadDocument(inputSource, getEntityResolver(), this.errorHandler,
				getValidationModeForResource(resource), isNamespaceAware());
	}

	/**
	 * 确定指定{@link Resource}的验证模式.
	 * 如果未配置显式验证模式, 则验证模式将从给定资源获取{@link #detectValidationMode detected}.
	 * <p>如果您想完全控制验证模式, 即使设置了{@link #VALIDATION_AUTO}以外的其他内容, 也可以重写此方法.
	 * @see #detectValidationMode
	 */
	protected int getValidationModeForResource(Resource resource) {
		int validationModeToUse = getValidationMode();
		if (validationModeToUse != VALIDATION_AUTO) {
			return validationModeToUse;
		}
		int detectedMode = detectValidationMode(resource);
		if (detectedMode != VALIDATION_AUTO) {
			return detectedMode;
		}
		// 嗯, 我们没有得到一个明确的指示...
		// 让我们假设是XSD, 显然在检测停止之前(在找到文档的根tag之前)还没有找到DTD声明.
		return VALIDATION_XSD;
	}

	/**
	 * 检车对所提供的{@link Resource}标识的XML文件执行哪种验证.
	 * 如果文件具有{@code DOCTYPE}定义, 则使用DTD验证, 否则假设XSD验证.
	 * <p>如果要自定义{@link #VALIDATION_AUTO}模式的解析, 请重写此方法.
	 */
	protected int detectValidationMode(Resource resource) {
		if (resource.isOpen()) {
			throw new BeanDefinitionStoreException(
					"Passed-in Resource [" + resource + "] contains an open stream: " +
					"cannot determine validation mode automatically. Either pass in a Resource " +
					"that is able to create fresh streams, or explicitly specify the validationMode " +
					"on your XmlBeanDefinitionReader instance.");
		}

		InputStream inputStream;
		try {
			inputStream = resource.getInputStream();
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"Unable to determine validation mode for [" + resource + "]: cannot open InputStream. " +
					"Did you attempt to load directly from a SAX InputSource without specifying the " +
					"validationMode on your XmlBeanDefinitionReader instance?", ex);
		}

		try {
			return this.validationModeDetector.detectValidationMode(inputStream);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException("Unable to determine validation mode for [" +
					resource + "]: an error occurred whilst reading from the InputStream.", ex);
		}
	}

	/**
	 * 注册给定DOM文档中包含的bean定义. 由{@code loadBeanDefinitions}命名.
	 * <p>创建parser类的新实例并在其上调用{@code registerBeanDefinitions}.
	 * @param doc DOM文档
	 * @param resource 资源描述符(用于context信息)
	 * @return 找到的bean定义的数量
	 * @throws BeanDefinitionStoreException 如果出现解析错误
	 * @see #loadBeanDefinitions
	 * @see #setDocumentReaderClass
	 * @see BeanDefinitionDocumentReader#registerBeanDefinitions
	 */
	public int registerBeanDefinitions(Document doc, Resource resource) throws BeanDefinitionStoreException {
		BeanDefinitionDocumentReader documentReader = createBeanDefinitionDocumentReader();
		int countBefore = getRegistry().getBeanDefinitionCount();
		documentReader.registerBeanDefinitions(doc, createReaderContext(resource));
		return getRegistry().getBeanDefinitionCount() - countBefore;
	}

	/**
	 * 创建{@link BeanDefinitionDocumentReader}, 用于从XML文档中实际读取bean定义.
	 * <p>默认实现实例化指定的"documentReaderClass".
	 * @see #setDocumentReaderClass
	 */
	protected BeanDefinitionDocumentReader createBeanDefinitionDocumentReader() {
		return BeanUtils.instantiateClass(this.documentReaderClass);
	}

	/**
	 * 创建要传递给文档reader的{@link XmlReaderContext}.
	 */
	public XmlReaderContext createReaderContext(Resource resource) {
		return new XmlReaderContext(resource, this.problemReporter, this.eventListener,
				this.sourceExtractor, this, getNamespaceHandlerResolver());
	}

	/**
	 * 如果之前未设置, 请延迟创建默认NamespaceHandlerResolver.
	 * @see #createDefaultNamespaceHandlerResolver()
	 */
	public NamespaceHandlerResolver getNamespaceHandlerResolver() {
		if (this.namespaceHandlerResolver == null) {
			this.namespaceHandlerResolver = createDefaultNamespaceHandlerResolver();
		}
		return this.namespaceHandlerResolver;
	}

	/**
	 * 如果未指定, 则创建{@link NamespaceHandlerResolver}的默认实现.
	 * <p>默认实现返回{@link DefaultNamespaceHandlerResolver}的实例.
	 * @see DefaultNamespaceHandlerResolver#DefaultNamespaceHandlerResolver(ClassLoader)
	 */
	protected NamespaceHandlerResolver createDefaultNamespaceHandlerResolver() {
		ClassLoader cl = (getResourceLoader() != null ? getResourceLoader().getClassLoader() : getBeanClassLoader());
		return new DefaultNamespaceHandlerResolver(cl);
	}

}
