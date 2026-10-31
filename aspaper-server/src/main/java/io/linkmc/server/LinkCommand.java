package io.linkmc.server;

import io.linkmc.api.LinkApi;
import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Administrative template tooling; never advertised as durable gameplay commands. */
final class LinkCommand extends Command {
    private final LinkApi api;
    private final LinkCompletions completions;
    LinkCommand(LinkApi api,LinkCompletions completions){
        super("link","Link template tools","/link <templates|worlds|editor|publish|create|load|save|unload|delete>",List.of());
        this.api=api;this.completions=completions;setPermission("link.admin");
    }
    static void register(LinkApi api,LinkCompletions completions){
        var server=org.bukkit.Bukkit.getServer();
        if(server.getPluginManager().getPermission("link.admin")==null)
            server.getPluginManager().addPermission(new Permission("link.admin",PermissionDefault.OP));
        server.getCommandMap().register("link",new LinkCommand(api,completions));
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        try {
            if(args.length==0){sender.sendMessage(getUsage());return true;}
            CompletionStage<?> operation=switch(args[0].toLowerCase(java.util.Locale.ROOT)){
                case "templates" -> api.worlds().templates();
                case "worlds" -> api.worlds().loaded();
                case "editor" -> {count(args,2);yield api.worlds().createEditor(args[1]);}
                case "publish" -> {count(args,4);yield api.worlds().publish(args[1],args[2],Integer.parseInt(args[3]));}
                case "create" -> {
                    count(args,5);
                    if(!args[4].equals("persistent")&&!args[4].equals("temporary"))throw new IllegalArgumentException("Use persistent or temporary");
                    yield api.worlds().create(args[1],args[2],Integer.parseInt(args[3]),args[4].equals("persistent"));
                }
                case "load" -> {count(args,2);yield api.worlds().load(args[1]);}
                case "save" -> {count(args,2);yield api.worlds().save(args[1]);}
                case "unload" -> {count(args,2);yield api.worlds().unload(args[1]);}
                case "delete" -> {count(args,2);yield api.worlds().delete(args[1]);}
                default -> throw new IllegalArgumentException(getUsage());
            };
            operation.whenComplete((result,error) -> MinecraftServer.getServer().execute(() -> {
                if(error!=null){
                    // SQL errors can contain operational details; log them, don't send to players.
                    MinecraftServer.LOGGER.error("Link administrative operation failed",error);
                    sender.sendMessage("Link operation failed. Check the server log.");
                }else sender.sendMessage(result==null?"Link operation completed.":result.toString());
            }));
        }catch(IllegalArgumentException e){sender.sendMessage(e.getMessage());}
        return true;
    }
    private static void count(String[] args,int expected){if(args.length!=expected)throw new IllegalArgumentException("Incorrect arguments. See Link documentation.");}
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        return testPermissionSilent(sender)?completions.admin(args):List.of();
    }
}
