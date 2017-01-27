package net.kodehawa.mantarobot;

import com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.JDAInfo;
import net.dv8tion.jda.core.entities.Game;
import net.kodehawa.mantarobot.core.LoadState;
import net.kodehawa.mantarobot.core.listeners.MantaroListener;
import net.kodehawa.mantarobot.data.Config;
import net.kodehawa.mantarobot.data.Data;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.log.DiscordLogBack;
import net.kodehawa.mantarobot.log.SimpleLogToSLF4JAdapter;
import net.kodehawa.mantarobot.modules.Module;
import net.kodehawa.mantarobot.utils.Async;
import net.kodehawa.mantarobot.utils.ThreadPoolHelper;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Future;

import static net.kodehawa.mantarobot.MantaroInfo.VERSION;
import static net.kodehawa.mantarobot.core.LoadState.*;

public class MantaroBot {
	private static final Logger LOGGER = LoggerFactory.getLogger("MantaroBot");
	private static JDA jda;
	private static LoadState status = PRELOAD;

	public static JDA getJDA() {
		return jda;
	}

	public static LoadState getStatus() {
		return status;
	}

	private static void init() throws Exception {
		SimpleLogToSLF4JAdapter.install();
		LOGGER.info("MantaroBot starting...");

		Config config = MantaroData.getConfig().get();

		Future<Set<Class<? extends Module>>> classesAsync = ThreadPoolHelper.defaultPool().getThreadPool()
			.submit(() -> new Reflections("net.kodehawa.mantarobot.commands").getSubTypesOf(Module.class));

		status = LOADING;
		jda = new JDABuilder(AccountType.BOT)
			.setToken(config.token)
			.addListener(new MantaroListener())
			.setAudioSendFactory(new NativeAudioSendFactory())
			.setAutoReconnect(true)
			.setGame(Game.of("Hold your seatbelts!"))
			.buildBlocking();
		DiscordLogBack.enable();
		status = LOADED;
		LOGGER.info("[-=-=-=-=-=- MANTARO STARTED -=-=-=-=-=-]");
		LOGGER.info("Started bot instance.");
		LOGGER.info("Started MantaroBot " + VERSION + " on JDA " + JDAInfo.VERSION);

		Data data = MantaroData.getData().get();
		Random r = new Random();

		List<String> splashes = MantaroData.getSplashes().get();

		Runnable changeStatus = () -> {
			int i = r.nextInt(splashes.size() - 1);
			jda.getPresence().setGame(Game.of(data.defaultPrefix + "help | " + splashes.get(i)));
			LOGGER.info("Changed status to: " + splashes.get(i));
		};

		changeStatus.run();

		Async.startAsyncTask("Splash Thread", changeStatus, 600);

		Set<Module> modules = new HashSet<>();
		for (Class<? extends Module> c : classesAsync.get()) {
			try {
				modules.add(c.newInstance());
			} catch (InstantiationException e) {
				LOGGER.error("Cannot initialize a command", e);
			} catch (IllegalAccessException e) {
				LOGGER.error("Cannot access a command class!", e);
			}
		}

		status = POSTLOAD;
		LOGGER.info("Finished loading basic components. Status is now set to POSTLOAD");
		LOGGER.info("Loaded " + Module.Manager.commands.size() + " commands");

		modules.forEach(Module::onPostLoad);
	}

	public static void main(String[] args) {
		try {
			init();
		} catch (Exception e) {
			DiscordLogBack.disable();
			LOGGER.error("Could not complete Main Thread Routine!", e);
			LOGGER.error("Cannot continue! Exiting program...");
			System.exit(-1);
		}
	}
}
