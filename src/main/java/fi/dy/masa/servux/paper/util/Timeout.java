package fi.dy.masa.servux.paper.util;

/**
 * Minimal Paper-side mirror of Servux's {@code util.Timeout} (Fabric side) - tracks the last tick
 * a tracked chunk's structure data was (re)sent, to know when it needs to be refreshed.
 *
 * @see <a href="https://github.com/MattLavalleeMA/servux/blob/13642bc72085937798c215ec291a1ec6968aef64/src/main/java/fi/dy/masa/servux/util/Timeout.java">Timeout.java (Fabric reference)</a>
 */
public class Timeout
{
    private int lastSync;

    public Timeout(int currentTick)
    {
        this.lastSync = currentTick;
    }

    public boolean needsUpdate(int currentTick, int timeoutTicks)
    {
        return currentTick - this.lastSync >= timeoutTicks;
    }

    public void setLastSync(int tickCounter)
    {
        this.lastSync = tickCounter;
    }
}
