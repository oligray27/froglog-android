package uk.co.froglog.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

import uk.co.froglog.app.remote.FrogLogRemotePlugin;
import uk.co.froglog.app.widgets.FrogLogWidgetsPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(PageHeightPlugin.class);
        registerPlugin(FrogLogWidgetsPlugin.class);
        registerPlugin(FrogLogRemotePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
