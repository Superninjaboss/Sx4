package com.sx4.bot.formatter.parser;

import com.sx4.bot.formatter.function.FormatterEvent;
import com.sx4.bot.formatter.function.FormatterFunction;

public class GreaterThanFunction extends FormatterFunction<Number> {

	public GreaterThanFunction() {
		super(Number.class, "gt", "Checks if a number is greater than another number");
	}

	public boolean parse(FormatterEvent<Number> event, Double number) {
		return event.getObject().doubleValue() > number;
	}

}
