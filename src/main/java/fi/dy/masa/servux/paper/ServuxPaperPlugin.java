package fi.dy.masa.servux.paper;

import org.bukkit.plugin.java.JavaPlugin;

import fi.dy.masa.servux.paper.commands.ServuxPaperCommand;
import fi.dy.masa.servux.paper.network.EntitiesChannel;
import fi.dy.masa.servux.paper.network.HudMetadataChannel;
import fi.dy.masa.servux.paper.network.StructuresChannel;
import fi.dy.masa.servux.paper.placement.EasyPlace;
import fi.dy.masa.servux.paper.syncmatica.Syncmatica;

/**
 * Entry point for the PaperMC port of Servux's MiniHUD server-side protocol support.
 *
 * @see <a href="https://github.com/maruohon/servux">Servux (Fabric, upstream)</a>
 */
public class ServuxPaperPlugin extends JavaPlugin
{
    private HudMetadataChannel hudMetadataChannel;
    private StructuresChannel structuresChannel;
    private EntitiesChannel entitiesChannel;
    private EasyPlace easyPlace;
    private Syncmatica syncmatica;

    @Override
    public void onEnable()
    {
        this.saveDefaultConfig();
        ServuxPaperConfig.load(this.getConfig());

        ServuxPaperReference.init(this);
        ServuxPaperReference.setDebugLogEnabled(ServuxPaperConfig.debugLog());

        ServuxPaperCommand.register(this);

        this.hudMetadataChannel = new HudMetadataChannel(this);
        this.hudMetadataChannel.register();

        this.structuresChannel = new StructuresChannel(this);
        this.structuresChannel.register();

        this.entitiesChannel = new EntitiesChannel(this);
        this.entitiesChannel.register();

        this.easyPlace = new EasyPlace(this);
        this.easyPlace.register();
        this.syncmatica = new Syncmatica(this);
        try {
            this.syncmatica.register();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot initialize Syncmatica storage", error);
        }

        this.getSLF4JLogger().info("Servux Paper enabled - MiniHUD, Easy Place V3 and Syncmatica registered.");
    }

    @Override
    public void onDisable()
    {
        if (this.easyPlace != null) this.easyPlace.unregister();
        if (this.syncmatica != null) this.syncmatica.unregister();
        if (this.hudMetadataChannel != null)
        {
            this.hudMetadataChannel.unregister();
            this.hudMetadataChannel = null;
        }

        if (this.structuresChannel != null)
        {
            this.structuresChannel.unregister();
            this.structuresChannel = null;
        }

        if (this.entitiesChannel != null)
        {
            this.entitiesChannel.unregister();
            this.entitiesChannel = null;
        }
    }
}
