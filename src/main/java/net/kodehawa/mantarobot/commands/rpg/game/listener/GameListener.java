package net.kodehawa.mantarobot.commands.rpg.game.listener;

import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.commands.rpg.entity.player.EntityPlayer;
import net.kodehawa.mantarobot.commands.rpg.world.TextChannelWorld;
import net.kodehawa.mantarobot.core.listeners.OptimizedListener;

public class GameListener extends OptimizedListener<GuildMessageReceivedEvent> {

    public GameListener(){
        super(GuildMessageReceivedEvent.class);
    }

    @Override
    public void event(GuildMessageReceivedEvent event) {
        TextChannelWorld world = TextChannelWorld.of(event);
        EntityPlayer player = EntityPlayer.getPlayer(event);
        if(world.getRunningGames().isEmpty() || player.getGame() == null){
            return;
        }

        if(world == null || player == null) return;

        world.getRunningGames().get(player).call(event, player);
    }
}