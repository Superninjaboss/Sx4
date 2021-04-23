package com.sx4.bot.formatter.parser;

import com.sx4.bot.formatter.function.FormatterEvent;
import com.sx4.bot.formatter.function.FormatterFunction;
import com.sx4.bot.utility.StringUtility;

public class SubstringFunction extends FormatterFunction<String> {

	public SubstringFunction() {
		super(String.class, "substring");
	}

	public String parse(FormatterEvent event, Integer start, Integer end) {
		return StringUtility.substring((String) event.getObject(), start, end);
	}

}