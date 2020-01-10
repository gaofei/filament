/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.filament.gltf

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.animation.LinearInterpolator

import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*

import java.nio.ByteBuffer
import java.nio.channels.Channels

class MainActivity : Activity() {

    // We are using the gltfio library, so init the AssetLoader rather than Filament.
    companion object {
        init {
            AssetLoader.init()
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var choreographer: Choreographer
    private val frameScheduler = FrameCallback()
    private val animator = ValueAnimator.ofFloat(0.0f, (2.0 * PI).toFloat())
    private var width = 720
    private var height = 1280
    private var strafing = false

    // gltfio and utils objects
    private lateinit var manipulator: Manipulator
    private lateinit var assetLoader: AssetLoader
    private lateinit var filamentAsset: FilamentAsset

    // core filament objects
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var swapChain: SwapChain? = null
    @Entity private var light = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        choreographer = Choreographer.getInstance()

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.setDesiredSize(width, height)
        uiHelper.attachTo(surfaceView)

        manipulator = Manipulator.Builder()
                .targetPosition(0.0f, 0.0f, -4.0f)
                .viewport(width, height)
                .build(Manipulator.Mode.ORBIT)

        surfaceView.setOnTouchListener { v, event ->
            val x = event.getX(0).toInt()
            val y = height - event.getY(0).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    manipulator.grabEnd()
                    strafing = false
                    manipulator.grabBegin(x, y, strafing)
                    Log.e("gltf-viewer", "down ${x} ${y}")
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 2 && !strafing) {
                        strafing = true
                        manipulator.grabEnd()
                        manipulator.grabBegin(x, y, strafing)
                        Log.e("gltf-viewer", "move strafe ${x} ${y}")
                    } else {
                        Log.e("gltf-viewer", "move ${x} ${y} -- ${event.pointerCount}")
                    }
                    if (strafing && event.pointerCount == 1) {
                        // Do nothing when a single touch is lifted from a strafe gesture.
                        // Without this explicit test, users often see "jumpiness" in the camera.
                    } else {
                        manipulator.grabUpdate(x, y)
                    }
                }
                MotionEvent.ACTION_CANCEL-> {
                    Log.e("gltf-viewer", "cancel")
                    manipulator.grabEnd()
                }
                MotionEvent.ACTION_UP -> {
                    Log.e("gltf-viewer", "up")
                    manipulator.grabEnd()
                }
            }
            true
        }

        // Engine, Renderer, Scene, View, Camera
        // -------------------------------------

        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera()
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        view.scene = scene
        view.camera = camera

        // IndirectLight and SkyBox
        // ------------------------

        val ibl = "venetian_crossroads_2k"

        readUncompressedAsset("envs/$ibl/${ibl}_ibl.ktx").let {
            scene.indirectLight = KtxLoader.createIndirectLight(engine, it, KtxLoader.Options())
            scene.indirectLight!!.intensity = 50_000.0f
        }

        readUncompressedAsset("envs/$ibl/${ibl}_skybox.ktx").let {
            scene.skybox = KtxLoader.createSkybox(engine, it, KtxLoader.Options())
        }

        camera.lookAt(
                0.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                0.0, 1.0, 0.0)

        // glTF Entities, Textures, and Materials
        // --------------------------------------

        assetLoader = AssetLoader(engine, MaterialProvider(engine), EntityManager.get())

        filamentAsset = assets.open("models/scene.gltf").use { input ->
            val bytes = ByteArray(input.available())
            input.read(bytes)
            assetLoader.createAssetFromJson(ByteBuffer.wrap(bytes))!!
        }

        var resourceLoader = ResourceLoader(engine)
        for (uri in filamentAsset.resourceUris) {
            val buffer = readUncompressedAsset("models/$uri")
            resourceLoader.addResourceData(uri, buffer)
        }
        resourceLoader.loadResources(filamentAsset)

        Log.i("gltf-viewer", "Adding ${filamentAsset.entities.size} entities to scene...")
        scene.addEntities(filamentAsset.entities)

        // Transform to unit cube
        // ----------------------

        val tm = engine.transformManager
        val center = filamentAsset.boundingBox.center.let { Float3(it[0], it[1], it[2]) }
        val halfExtent  = filamentAsset.boundingBox.halfExtent.let { Float3(it[0], it[1], it[2]) }
        val maxExtent = 2.0f * max(halfExtent)
        val scaleFactor = 2.0f / maxExtent
        center.z = center.z + 4.0f / scaleFactor
        var xform = scale(Float3(scaleFactor)) * translation(Float3(-center))
        tm.setTransform(tm.getInstance(filamentAsset.root), transpose(xform).toFloatArray())

        // Light Sources
        // -------------

        light = EntityManager.get().create()

        val (r, g, b) = Colors.cct(6_500.0f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(r, g, b)
                .intensity(300_000.0f)
                .direction(0.0f, -1.0f, 0.0f)
                .castShadows(true)
                .build(engine, light)

        scene.addEntity(light)

        // Start Animation
        // ---------------

        animator.interpolator = LinearInterpolator()
        animator.duration = 1_000_000
        animator.repeatMode = ValueAnimator.RESTART
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { a ->
            if (filamentAsset.animator.animationCount > 0) {
                val elapsedTimeInSeconds = a.currentPlayTime.toFloat() / 1000.0f
                filamentAsset.animator.applyAnimation(0, elapsedTimeInSeconds)
                filamentAsset.animator.updateBoneMatrices()
            }
        }
        animator.start()
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameScheduler)
        animator.start()
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameScheduler)
        animator.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop the animation and any pending frame
        choreographer.removeFrameCallback(frameScheduler)
        animator.cancel()

        // Always detach the surface before destroying the engine
        uiHelper.detach()

        assetLoader.destroyAsset(filamentAsset)
        assetLoader.destroy()

        engine.destroyEntity(light)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCamera(camera)

        // Engine.destroyEntity() destroys Filament components only, not the entity itself.
        val entityManager = EntityManager.get()
        entityManager.destroy(light)

        // Destroying the engine will free up any resource you may have forgotten
        // to destroy, but it's recommended to do the cleanup properly
        engine.destroy()
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)

            var eyepos = floatArrayOf(0.0f, 0.0f, 0.0f)
            var target = floatArrayOf(0.0f, 0.0f, 0.0f)
            var upward = floatArrayOf(0.0f, 0.0f, 0.0f)
            manipulator.getLookAt(eyepos, target, upward)

            camera.lookAt(
                    eyepos[0].toDouble(), eyepos[1].toDouble(), eyepos[2].toDouble(),
                    target[0].toDouble(), target[1].toDouble(), target[2].toDouble(),
                    upward[0].toDouble(), upward[1].toDouble(), upward[2].toDouble())

            if (uiHelper.isReadyToRender) {
                if (renderer.beginFrame(swapChain!!)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }

    inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
        }

        override fun onDetachedFromSurface() {
            swapChain?.let {
                engine.destroySwapChain(it)
                // Required to ensure we don't return before Filament is done executing the
                // destroySwapChain command, otherwise Android might destroy the Surface
                // too early
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            this@MainActivity.width = width
            this@MainActivity.height = height
            view.viewport = Viewport(0, 0, width, height)
            val aspect = width.toDouble() / height.toDouble()
            camera.setProjection(45.0, aspect, 0.5, 10000.0, Camera.Fov.VERTICAL)
            manipulator.setViewport(width, height)
        }
    }

    private fun readUncompressedAsset(assetName: String): ByteBuffer {
        Log.i("gltf-viewer", "Reading ${assetName}...")
        assets.openFd(assetName).use { fd ->
            val input = fd.createInputStream()
            val dst = ByteBuffer.allocate(fd.length.toInt())

            val src = Channels.newChannel(input)
            src.read(dst)
            src.close()

            return dst.apply { rewind() }
        }
    }
}
