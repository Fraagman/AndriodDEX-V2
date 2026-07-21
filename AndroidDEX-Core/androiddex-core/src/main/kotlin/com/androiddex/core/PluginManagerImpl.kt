package com.androiddex.core

import java.util.concurrent.ConcurrentHashMap

class PluginManagerImpl : PluginManager {
    private val plugins = ConcurrentHashMap<String, Plugin>()

    override fun registerPlugin(plugin: Plugin) {
        plugins[plugin.id] = plugin
        println("PluginManager: Registered plugin ${plugin.id} v${plugin.version}")
    }

    override fun getPlugin(id: String): Plugin? {
        return plugins[id]
    }

    override fun initializeAll(capabilityManager: CapabilityManager) {
        plugins.values.forEach { plugin ->
            if (plugin.validate(capabilityManager)) {
                plugin.initialize()
                println("PluginManager: Initialized plugin ${plugin.id}")
            } else {
                println("PluginManager: Plugin ${plugin.id} failed capability validation. Skipping.")
            }
        }
    }

    override fun shutdownAll() {
        plugins.values.forEach { plugin ->
            plugin.shutdown()
        }
        plugins.clear()
    }
}
