package com.sx4.bot.formatter.parser;

import com.sx4.bot.formatter.function.FormatterCondition;
import com.sx4.bot.formatter.function.FormatterEvent;
import com.sx4.bot.formatter.function.FormatterFunction;

public class ElseFunction extends FormatterFunction<FormatterCondition> {

	public ElseFunction() {
		super(FormatterCondition.class, "else", "The value to be returned if the condition is false");
	}

	public Object parse(FormatterEvent<FormatterCondition> event, Object object) {
		return event.getObject().orElse(object);
	}

}
