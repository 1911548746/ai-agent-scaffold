/**
 * 应用层：承载由入站适配器触发的用例编排。
 *
 * <p>本层依赖领域模型和端口，不依赖 HTTP Controller、DTO 或 Spring 注解。对象由 app 模块
 * 显式装配，使同一用例可以被 HTTP、Job 或消息监听器复用。</p>
 */
package com.hh.application;
