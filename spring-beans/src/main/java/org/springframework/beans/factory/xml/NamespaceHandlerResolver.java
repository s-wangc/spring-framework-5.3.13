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

package org.springframework.beans.factory.xml;

import org.springframework.lang.Nullable;

/**
 * 由{@link org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader}
 * 用于特定namespace URI的{@link NamespaceHandler}实现.
 *
 * @author Rob Harrop
 * @since 2.0
 * @see NamespaceHandler
 * @see org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader
 */
@FunctionalInterface
public interface NamespaceHandlerResolver {

	/**
	 * 解析namespace URI并返回定位的{@link NamespaceHandler}实现implementation.
	 * @param namespaceUri 相关的namespace URI.
	 * @return 定位到的{@link NamespaceHandler} (可能是{@code null})
	 */
	@Nullable
	NamespaceHandler resolve(String namespaceUri);

}
