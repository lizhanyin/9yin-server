package com.swingfrog.summer.web.view;

import com.alibaba.fastjson2.JSON;

public class JSONView extends TextView {

	public static JSONView of(JSON json) {
		return new JSONView(json);
	}

	public JSONView(JSON json) {
		// fastjson2 的 JSON 基类无实例 toJSONString（v1 有），改走静态序列化。
		super(JSON.toJSONString(json));
	}

	@Override
	public String getContentType() {
		return "application/json";
	}

	@Override
	public String toString() {
		return "JSONView";
	}
	
}
