/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.r2dbc.connection.init;

import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;

/**
 * Root of the hierarchy of data access exceptions that are related to processing
 * of SQL scripts.
 *
 * @author Sam Brannen
 * @author Mark Paluch
 * @since 5.3
 */
@SuppressWarnings("serial")
public abstract class ScriptException extends DataAccessException {

	/**
	 * 创建一个新的{@code ScriptException}.
	 * @param message 详细信息
	 */
	public ScriptException(String message) {
		super(message);
	}

	/**
	 * 创建一个新的{@code ScriptException}.
	 * @param message 详细信息
	 * @param cause 根本原因
	 */
	public ScriptException(String message, @Nullable Throwable cause) {
		super(message, cause);
	}

}
