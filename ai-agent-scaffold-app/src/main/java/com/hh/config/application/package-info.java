/**
 * application 用例的 Spring 组合根。
 *
 * <p>application 模块保持对 Spring 的隔离，本包负责将其用例实现与运行时、归档等出站端口
 * 组装为容器 Bean，供 HTTP、Job 或 Listener 等入站适配器复用。</p>
 *
 * <p>本包只声明依赖关系，不承载用例编排规则或基础设施实现。</p>
 */
package com.hh.config.application;

