package com.sx4.bot.category;

import com.sx4.bot.core.Sx4Category;

public class Category {
	
	public static final Sx4Category ALL = new Sx4Category("All", "This module contains every command on the bot");

	public static final Sx4Category MODERATION = new Sx4Category("Moderation", "This module has commands which will help you keep rules in place in your server", Category.ALL, "Mod");
	public static final Sx4Category NOTIFICATIONS = new Sx4Category("Notifications", "This modules has commands which will give you notifications for various things", Category.ALL);
	public static final Sx4Category LOGGING = new Sx4Category("Logging", "This module has commands which will help you log things which happen in your server", Category.ALL);
	public static final Sx4Category DEVELOPER = new Sx4Category("Developer", "This modules contains command which are for developers only", Category.ALL, "Dev");
	public static final Sx4Category MISC = new Sx4Category("Miscellaneous", "This module has a mix of commands which are useful in their own way", Category.ALL, "Misc");
	
	public static final Sx4Category[] ALL_ARRAY = {ALL, MODERATION, NOTIFICATIONS, LOGGING, DEVELOPER, MISC};
	
}
