package cn.ffcs.tool.zookeeper;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import java.util.concurrent.CountDownLatch;

/**
 * Zookeeper连接成功观察者
 * 
 * <pre>
 * 与Zookeeper连接成功Watcher会触发一个事件KeeperState.SyncConnected
 * </pre>
 * 
 * @author czllfy
 */
public class ConnectedWatcher implements Watcher {

    private CountDownLatch connectedLatch;

    ConnectedWatcher(CountDownLatch connectedLatch){
        this.connectedLatch = connectedLatch;
    }

    public void process(WatchedEvent event) {
        if (event.getState() == KeeperState.SyncConnected) {
            connectedLatch.countDown();
        }
    }
}
