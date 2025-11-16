/*
- Fix Fly-by Mechanics 
- Fix Star Apparence
- Apply Rotation
- Astroid Belt & Rings
- Add Sky Dome
- Lighting & Shadows
   - Sun as Centeral Light Source
   - Moon Shadows
   - Eclipse
- Add Additional Button Mechanics
- Add Text Canvas
*/

package code;

import java.io.*;
import java.nio.*;
import java.lang.Math;
import javax.swing.*;
import static com.jogamp.opengl.GL4.*;
import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.*;
import com.jogamp.common.nio.Buffers;
import org.joml.*;
import java.awt.event.*;


public class Code extends JFrame implements GLEventListener, KeyListener {
    private GLCanvas myCanvas;
    private Animator animator;
    private int renderingProgram;
    private int vao[] = new int[1];
    private int vbo[] = new int[15];
    private Sphere mySphere;
    private int numSphereVerts, numOrbitVerts;
    
    //Camera & Projection
    private float cameraX, cameraY, cameraZ;
    private float aspect;
    private int mvLoc, pLoc;
    private FloatBuffer vals = Buffers.newDirectFloatBuffer(16);
    private Matrix4fStack mvStack = new Matrix4fStack(10);
    private Matrix4f pMat = new Matrix4f();
    private Matrix4f vMat = new Matrix4f();
    private boolean projectionNeedsUpdate = true;
    
    //Animation
    private long startTime, stageStartTime;
    private int currentStage = 0;
    private boolean transitionComplete = false;
    private boolean topDownMode = true;
    private boolean scaledViewMode = false;
    private Vector3f cameraOffset = new Vector3f(0.0f, 0.0f, 8.0f);
    
    //Assc. Variables
    private CelestialBody[] planets;
    private TransitionStage[] stages;
    private int sunTexture, shootingStarTexture;
    
    //Stores Data for Objects    
    private static class CelestialBody {
        String name;
        float orbitRadius, size, rotationSpeed, orbitSpeed;
        int texture;
        float[] moonOrbitRadii;
        float[] moonSizes;
        int[] moonTextures;
        float[] moonSpeeds;
        
        CelestialBody(String name, float orbitRadius, float size, float rotationSpeed, float orbitSpeed) {
            this.name = name;
            this.orbitRadius = orbitRadius;
            this.size = size;
            this.rotationSpeed = rotationSpeed;
            this.orbitSpeed = orbitSpeed;
        }
        
        //Earth's Moon
        CelestialBody withMoon(float moonOrbitRadius, float moonSize, int moonTexture) {
            this.moonOrbitRadii = new float[]{moonOrbitRadius};
            this.moonSizes = new float[]{moonSize};
            this.moonTextures = new int[]{moonTexture};
            this.moonSpeeds = new float[]{0.3f};
            return this;
        }
        
        //Jupiter's Moons
        CelestialBody withMoons(float[] moonOrbitRadii, float[] moonSizes, int[] moonTextures, float[] moonSpeeds) {
            this.moonOrbitRadii = moonOrbitRadii;
            this.moonSizes = moonSizes;
            this.moonTextures = moonTextures;
            this.moonSpeeds = moonSpeeds;
            return this;
        }
    }
    
    //Stores Data for Fly-By
    private static class TransitionStage {
        float endOffset, controlPoint1, controlPoint2, camDist;
        TransitionStage(float endOffset, float cp1, float cp2, float camDist) {
            this.endOffset = endOffset;
            this.controlPoint1 = cp1;
            this.controlPoint2 = cp2;
            this.camDist = camDist;
        }
    }
    
    //Stores Control Points for Bezier Calculation
    private static class PathData {
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f p3 = new Vector3f();
    }
    
    //Stores Navagation Data for Shooting Star
    private static class NavigationState {
        float lookAtX, lookAtY, lookAtZ;
        float starX, starY, starZ, prevX, prevY, prevZ;
        boolean drawStar;
    }
    
    public Code() {
        setTitle("Solar System");
        setSize(1200, 1200);
        myCanvas = new GLCanvas();
        myCanvas.addGLEventListener(this);
        myCanvas.addKeyListener(this);
        myCanvas.setFocusable(true);
        myCanvas.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { myCanvas.requestFocus(); }
        });
        this.add(myCanvas);
        this.setVisible(true);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        myCanvas.requestFocus();
        startTime = stageStartTime = System.currentTimeMillis();       
        animator = new Animator(myCanvas);
        animator.start();
    }
    
    private void initializePlanets() {
        planets = new CelestialBody[] {
            new CelestialBody("Mercury", 40.0f, 0.06f, 1.0f, 0.4f),
            new CelestialBody("Venus", 80.0f, 0.14f, 0.8f, 0.3f),
            new CelestialBody("Earth", 107.0f, 0.15f, 0.6f, 0.2f)
                .withMoon(0.5f, 0.04f, 0),
            new CelestialBody("Mars", 150.0f, 0.08f, 0.7f, 0.1f),
            new CelestialBody("Jupiter", 520.0f, 0.8f, 0.4f, 0.05f)
                .withMoons(
                    new float[]{2.0f, 3.0f, 4.0f, 5.5f},
                    new float[]{0.045f, 0.04f, 0.065f, 0.06f},
                    new int[]{0, 0, 0, 0},
                    new float[]{0.8f, 0.5f, 0.35f, 0.2f}
                ),
            new CelestialBody("Saturn", 950.0f, 0.6f, 0.3f, 0.03f),
            new CelestialBody("Uranus", 1920.0f, 0.4f, 0.25f, 0.02f),
            new CelestialBody("Neptune", 3000.0f, 0.4f, 0.2f, 0.01f),
            new CelestialBody("Pluto", 3950.0f, 0.04f, 0.15f, 0.008f)
        };
    }
    
    private void initializeStages() {
        stages = new TransitionStage[] {
            new TransitionStage(2.0f, 15.0f, 0.7f, 3.0f),   // Mercury
            new TransitionStage(3.0f, 15.0f, 0.7f, 3.5f),   // Venus
            new TransitionStage(4.0f, 20.0f, 0.7f, 4.0f),   // Earth
            new TransitionStage(5.0f, 25.0f, 0.7f, 5.0f),   // Mars
            new TransitionStage(20.0f, 100.0f, 0.5f, 8.0f), // Jupiter
            new TransitionStage(25.0f, 150.0f, 0.5f, 10.0f),// Saturn
            new TransitionStage(8.0f, 300.0f, 0.5f, 6.0f),  // Uranus
            new TransitionStage(8.0f, 400.0f, 0.5f, 6.0f),  // Neptune
            new TransitionStage(2.5f, 500.0f, 0.5f, 3.5f)   // Pluto
        };
    }

    
     public void display(GLAutoDrawable drawable) {
        GL4 gl = (GL4) GLContext.getCurrentGL();
        gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        float tf = (System.currentTimeMillis() - stageStartTime) / 1000.0f;
        float gt = (float)(System.currentTimeMillis() / 100.0);
        
        gl.glUseProgram(renderingProgram);
        
        if (topDownMode) {
            displayTopDown(gl, gt);
        } else {
            displayNavigation(gl, tf, gt);
        }
    }
    
     private void displayTopDown(GL4 gl, float gt) {
        if (projectionNeedsUpdate) {
            aspect = (float) myCanvas.getWidth() / (float) myCanvas.getHeight();
            float viewSize = scaledViewMode ? 100.0f : 450.0f;
            pMat.identity().setOrtho(-viewSize * aspect, viewSize * aspect, -viewSize, viewSize, 0.1f, 5000.0f);
            gl.glUniformMatrix4fv(pLoc, 1, false, pMat.get(vals));
            projectionNeedsUpdate = false;
        }
        
        vMat.identity().lookAt(0.0f, 500.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
        mvStack.pushMatrix();
        mvStack.mul(vMat);
        setupVertexAttributes(gl);
        
        if (scaledViewMode) {
            renderTopDownView(gl, gt, true);
        } else {
            renderTopDownView(gl, gt, false);
        }
        
        mvStack.popMatrix();
    }
    
    private void displayNavigation(GL4 gl, float tf, float gt) {
        if (projectionNeedsUpdate) {
            aspect = (float) myCanvas.getWidth() / (float) myCanvas.getHeight();
            pMat.identity().setPerspective((float) Math.toRadians(60.0f), aspect, 0.1f, 5000.0f);
            gl.glUniformMatrix4fv(pLoc, 1, false, pMat.get(vals));
            projectionNeedsUpdate = false;
        }
        
        NavigationState state = calculateNavigationState(tf, gt);
        
        vMat.identity().lookAt(cameraX, cameraY, cameraZ, state.lookAtX, state.lookAtY, state.lookAtZ, 0, 1, 0);
        mvStack.pushMatrix();
        mvStack.mul(vMat);
        setupVertexAttributes(gl);
        
        if (state.drawStar) {
            drawShootingStar(gl, state.starX, state.starY, state.starZ, state.prevX, state.prevY, state.prevZ);
            setupVertexAttributes(gl);
        }
        
        if (currentStage <= 1) {
            renderSphere(gl, 0, 0, 0, 2.0f, gt * 0.5f, sunTexture);
        }
        
        renderVisibleBodies(gl, gt);
        mvStack.popMatrix();
    }


    public void init(GLAutoDrawable drawable) {
        GL4 gl = (GL4) GLContext.getCurrentGL();
        renderingProgram = Utils.createShaderProgram("code/vertShader.glsl", "code/fragShader.glsl");
        
        gl.glUseProgram(renderingProgram);
        mvLoc = gl.glGetUniformLocation(renderingProgram, "mv_matrix");
        pLoc = gl.glGetUniformLocation(renderingProgram, "p_matrix");
        int texLoc = gl.glGetUniformLocation(renderingProgram, "samp");
        if (texLoc >= 0) gl.glUniform1i(texLoc, 0);
        
        gl.glEnable(GL_DEPTH_TEST);
        gl.glDepthFunc(GL_LEQUAL);
        gl.glEnable(GL_CULL_FACE);
        gl.glFrontFace(GL_CCW);
        
        aspect = (float) myCanvas.getWidth() / (float) myCanvas.getHeight();
        pMat.identity().setPerspective((float) Math.toRadians(60.0f), aspect, 0.1f, 5000.0f);
        
        initializePlanets();
        initializeStages();
        setupVertices();
        setupOrbitLines();
        loadTextures();
        
        cameraX = 0.0f; cameraY = 0.0f; cameraZ = 8.0f;
    }

    
    private void loadTextures() {
        sunTexture = Utils.loadTexture("sun.jpg");
        shootingStarTexture = Utils.loadTexture("star.jpg");
        
        String[] textureFiles = {"mercury.jpg", "venus.jpg", "earth.jpg", "mars.jpg",
                                 "jupiter.jpg", "saturn.jpg", "uranus.jpg", "neptune.jpg", "pluto.jpg"};
        for (int i = 0; i < planets.length; i++) {
            planets[i].texture = Utils.loadTexture(textureFiles[i]);
        }
        
        planets[2].moonTextures[0] = Utils.loadTexture("moon.jpg");
        planets[4].moonTextures[0] = Utils.loadTexture("io.jpg");
        planets[4].moonTextures[1] = Utils.loadTexture("europa.jpg");
        planets[4].moonTextures[2] = Utils.loadTexture("ganymede.jpg");
        planets[4].moonTextures[3] = Utils.loadTexture("callisto.jpg");
    }
    
    private void setupVertices() {
        GL4 gl = (GL4) GLContext.getCurrentGL();
        mySphere = new Sphere(48);
        numSphereVerts = mySphere.getIndices().length;
        
        int[] indices = mySphere.getIndices();
        Vector3f[] vert = mySphere.getVertices();
        Vector2f[] tex = mySphere.getTexCoords();
        
        float[] pvalues = new float[indices.length*3];
        float[] tvalues = new float[indices.length*2];
        
        for (int i = 0; i < indices.length; i++) {
            pvalues[i*3] = vert[indices[i]].x;
            pvalues[i*3+1] = vert[indices[i]].y;
            pvalues[i*3+2] = vert[indices[i]].z;
            tvalues[i*2] = tex[indices[i]].x;
            tvalues[i*2+1] = tex[indices[i]].y;
        }
        
        gl.glGenVertexArrays(vao.length, vao, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glGenBuffers(15, vbo, 0);
        
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferData(GL_ARRAY_BUFFER, pvalues.length*4, Buffers.newDirectFloatBuffer(pvalues), GL_STATIC_DRAW);
        
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[1]);
        gl.glBufferData(GL_ARRAY_BUFFER, tvalues.length*4, Buffers.newDirectFloatBuffer(tvalues), GL_STATIC_DRAW);
    }
    
    private void setupOrbitLines() {
        GL4 gl = (GL4) GLContext.getCurrentGL();
        int segments = 100;
        numOrbitVerts = segments;
        
        for (int i = 0; i < planets.length; i++) {
            float radius = planets[i].orbitRadius;
            float[] orbitVerts = new float[segments * 3];
            for (int j = 0; j < segments; j++) {
                float angle = (float)(2.0 * Math.PI * j / segments);
                orbitVerts[j*3] = radius * (float)Math.cos(angle);
                orbitVerts[j*3+1] = 0.0f;
                orbitVerts[j*3+2] = radius * (float)Math.sin(angle);
            }
            gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[5 + i]);
            gl.glBufferData(GL_ARRAY_BUFFER, orbitVerts.length*4, Buffers.newDirectFloatBuffer(orbitVerts), GL_STATIC_DRAW);
        }
    }
   
    
    private void renderSphere(GL4 gl, float x, float y, float z, float scale, float rotation, int texture) {
        mvStack.pushMatrix();
        mvStack.translate(x, y, z);
        mvStack.pushMatrix();
        mvStack.rotate(rotation, 0.0f, 1.0f, 0.0f);
        mvStack.scale(scale, scale, scale);
        gl.glActiveTexture(GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_2D, texture);
        gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
        gl.glDrawArrays(GL_TRIANGLES, 0, numSphereVerts);
        mvStack.popMatrix();
        mvStack.popMatrix();
    }
    
    private void renderVisibleBodies(GL4 gl, float gt) {
        for (int i = 0; i < planets.length; i++) {
            if (currentStage >= i * 2 + 1 && currentStage <= i * 2 + 3) {
                CelestialBody planet = planets[i];
                Vector3f pos = getPlanetPosition(i, gt);
                renderSphere(gl, pos.x, pos.y, pos.z, planet.size, gt * planet.rotationSpeed, planet.texture);
                
                if (planet.moonTextures != null) {
                    for (int m = 0; m < planet.moonTextures.length; m++) {
                        float moonAngle = gt * planet.moonSpeeds[m];
                        float moonX = pos.x + (float)Math.sin(moonAngle) * planet.moonOrbitRadii[m];
                        float moonZ = pos.z + (float)Math.cos(moonAngle) * planet.moonOrbitRadii[m];
                        renderSphere(gl, moonX, 0, moonZ, planet.moonSizes[m], gt * 0.4f, planet.moonTextures[m]);
                    }
                }
            }
        }
    }
    
    private void renderTopDownView(GL4 gl, float gt, boolean scaled) {
        float[] planetSizes, orbitRadii;
        float orbitScale = 1.0f, moonOrbitScale = 1.5f;
        float sunSize;
        
        if (scaled) {
            planetSizes = new float[]{1.5f, 2.0f, 2.2f, 1.8f, 5.0f, 4.5f, 3.5f, 3.5f, 1.2f};
            orbitRadii = new float[]{8.0f, 12.0f, 16.0f, 20.0f, 30.0f, 40.0f, 50.0f, 60.0f, 70.0f};
            sunSize = 4.0f;
        } else {
            planetSizes = new float[]{0.3f, 0.95f, 1.0f, 0.53f, 3.5f, 3.0f, 1.5f, 1.5f, 0.2f};
            orbitRadii = null;
            orbitScale = 0.1f;
            moonOrbitScale = 15.0f;
            sunSize = 5.0f;
        }
        
        //Draw orbit lines
        gl.glDisable(GL_TEXTURE_2D);
        gl.glLineWidth(1.0f);
        if (!scaled) mvStack.pushMatrix();
        if (!scaled) mvStack.scale(orbitScale, orbitScale, orbitScale);
        
        for (int i = 0; i < 9; i++) {
            if (scaled) {
                mvStack.pushMatrix();
                float scale = orbitRadii[i] / planets[i].orbitRadius;
                mvStack.scale(scale, scale, scale);
            }
            gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[5 + i]);
            gl.glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
            gl.glDisableVertexAttribArray(1);
            gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
            gl.glDrawArrays(GL_LINE_LOOP, 0, numOrbitVerts);
            if (scaled) mvStack.popMatrix();
        }
        if (!scaled) mvStack.popMatrix();
        
        gl.glEnable(GL_TEXTURE_2D);
        setupVertexAttributes(gl);
        renderSphere(gl, 0, 0, 0, sunSize, gt * 0.5f, sunTexture);
        
        //Render planets and moons
        for (int i = 0; i < planets.length; i++) {
            Vector3f pos = getPlanetPosition(i, gt);
            float angle = (float)Math.atan2(pos.x, pos.z);
            float px, pz;
            
            if (scaled) {
                px = (float)Math.sin(angle) * orbitRadii[i];
                pz = (float)Math.cos(angle) * orbitRadii[i];
            } else {
                px = pos.x * orbitScale;
                pz = pos.z * orbitScale;
            }
            
            renderSphere(gl, px, 0, pz, planetSizes[i], gt * planets[i].rotationSpeed, planets[i].texture);
            
            if (planets[i].moonTextures != null) {
                for (int m = 0; m < planets[i].moonTextures.length; m++) {
                    float moonAngle = gt * planets[i].moonSpeeds[m];
                    float moonOrbit = planets[i].moonOrbitRadii[m] * moonOrbitScale;
                    float moonSize = scaled ? 0.5f : 0.27f;
                    renderSphere(gl, px + (float)Math.sin(moonAngle) * moonOrbit, 0,
                               pz + (float)Math.cos(moonAngle) * moonOrbit, moonSize, gt * 0.4f, planets[i].moonTextures[m]);
                }
            }
        }
    }
    
    private void drawShootingStar(GL4 gl, float starX, float starY, float starZ, float prevX, float prevY, float prevZ) {
        mvStack.pushMatrix();
        mvStack.translate(starX, starY, starZ);
        
        float dirX = starX - prevX, dirY = starY - prevY, dirZ = starZ - prevZ;
        float dirLen = (float)Math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ);
        if (dirLen > 0.001f) { dirX /= dirLen; dirY /= dirLen; dirZ /= dirLen; }
        
        float angleY = (float)Math.atan2(dirX, dirZ);
        float angleX = (float)Math.asin(-dirY);
        
        gl.glEnable(GL_TEXTURE_2D);
        gl.glEnable(GL_BLEND);
        gl.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        gl.glActiveTexture(GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_2D, shootingStarTexture);
        
        //Leading 
        mvStack.pushMatrix();
        mvStack.rotate((float)Math.toDegrees(angleY), 0.0f, 1.0f, 0.0f);
        mvStack.rotate((float)Math.toDegrees(angleX), 1.0f, 0.0f, 0.0f);
        mvStack.scale(0.4f, 0.4f, 0.1f);
        gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
        gl.glDrawArrays(GL_TRIANGLES, 0, numSphereVerts);
        mvStack.popMatrix();
        
        //Trail 
        for (int i = 1; i <= 8; i++) {
            mvStack.pushMatrix();
            mvStack.translate(-dirX * 0.8f * i, -dirY * 0.8f * i, -dirZ * 0.8f * i);
            mvStack.rotate((float)Math.toDegrees(angleY), 0.0f, 1.0f, 0.0f);
            mvStack.rotate((float)Math.toDegrees(angleX), 1.0f, 0.0f, 0.0f);
            mvStack.scale(0.4f - i * 0.03f, 0.4f - i * 0.03f, 0.1f + i * 0.02f);
            gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
            gl.glDrawArrays(GL_TRIANGLES, 0, numSphereVerts);
            mvStack.popMatrix();
        }
        
        gl.glDisable(GL_BLEND);
        mvStack.popMatrix();
    }    
    
    private NavigationState calculateNavigationState(float tf, float gt) {
        NavigationState state = new NavigationState();
        
        if (currentStage == 0) {
            cameraX = cameraY = 0f; cameraZ = 8.0f;
            transitionComplete = true;
            state.drawStar = false;
        } else if (currentStage % 2 == 1 && currentStage <= 17) {
            calculateTransitionState(tf, gt, state);
        } else if (currentStage % 2 == 0 && currentStage >= 2) {
            calculateViewingState(gt, state);
        }
        
        return state;
    }
    
    private void calculateTransitionState(float tf, float gt, NavigationState state) {
        float animDuration = 6.5f;
        int planetIndex = (currentStage - 1) / 2;
        TransitionStage sd = stages[planetIndex];
        Vector3f planetPos = getPlanetPosition(planetIndex, gt);
        
        float rawT = Math.min(tf / animDuration, 1.0f);
        float t = easeInOutCubic(rawT);
        float tPrev = easeInOutCubic(Math.max(rawT - 0.02f, 0.0f));
        
        float endX = planetPos.x + sd.endOffset;
        float endY = planetPos.y + sd.endOffset * 0.5f;
        float endZ = planetPos.z + sd.endOffset;
        
        PathData path = buildTransitionPath(planetIndex, planetPos, sd, endX, endY, endZ);
        
        Vector3f starPos = evaluateCubicBezier(path, t, null);
        Vector3f prevStarPos = evaluateCubicBezier(path, tPrev, new Vector3f());    
        
        state.starX = starPos.x; state.starY = starPos.y; state.starZ = starPos.z;
        state.prevX = prevStarPos.x; state.prevY = prevStarPos.y; state.prevZ = prevStarPos.z;
        
        updateCameraFollowing(starPos, prevStarPos, planetPos, sd, planetIndex, t, rawT, state);
        state.drawStar = true;
        
        if (tf >= animDuration) {
            cameraOffset.set(endX, endY, endZ);
            currentStage++;
            stageStartTime = System.currentTimeMillis();
            transitionComplete = true;
        }
    }
    
    private void calculateViewingState(float gt, NavigationState state) {
        int planetIndex = (currentStage - 2) / 2;
        Vector3f planetPos = getPlanetPosition(planetIndex, gt);
        
        cameraX = cameraOffset.x;
        cameraY = cameraOffset.y;
        cameraZ = cameraOffset.z;
        
        state.lookAtX = planetPos.x;
        state.lookAtY = planetPos.y;
        state.lookAtZ = planetPos.z;
        
        transitionComplete = true;
        state.drawStar = false;
    }
    
    private PathData buildTransitionPath(int planetIndex, Vector3f planetPos, TransitionStage sd,
                                         float endX, float endY, float endZ) {
        PathData path = new PathData();
        path.p0.set(cameraOffset);
        path.p3.set(endX, endY, endZ);
        
        if (currentStage == 1) {
            float pathHeight = 35.0f;
            float pathDirX = endX - cameraOffset.x;
            float pathDirZ = endZ - cameraOffset.z;
            float pathLen = (float)Math.sqrt(pathDirX*pathDirX + pathDirZ*pathDirZ);
            if (pathLen > 0) { pathDirX /= pathLen; pathDirZ /= pathLen; }
            float perpX = -pathDirZ, perpZ = pathDirX;
            
            path.p1.set(
                cameraOffset.x + perpX * 15.0f + pathDirX * 8.0f,
                cameraOffset.y + pathHeight * 0.9f,
                cameraOffset.z + perpZ * 15.0f + pathDirZ * 8.0f
            );
            path.p2.set(
                endX - pathDirX * 10.0f + perpX * 8.0f,
                pathHeight * 0.7f,
                endZ - pathDirZ * 10.0f + perpZ * 8.0f
            );
        } else {
            float midX = (cameraOffset.x + endX) * 0.5f;
            float midZ = (cameraOffset.z + endZ) * 0.5f;
            float distToSun = (float)Math.sqrt(midX*midX + midZ*midZ);
            float pathDistXZ = (float)Math.sqrt((endX - cameraOffset.x)*(endX - cameraOffset.x) +
                                               (endZ - cameraOffset.z)*(endZ - cameraOffset.z));
            
            float heightMultiplier = 1.0f;
            float sunThreshold = Math.max(40.0f, pathDistXZ * 0.5f);
            if (distToSun < sunThreshold) {
                heightMultiplier = 2.0f + (1.0f - distToSun / sunThreshold) * 2.5f;
            }
            
            float pathHeight = Math.max(sd.controlPoint1, pathDistXZ * 0.25f) * heightMultiplier;
            float sunAvoidFactor = 4.0f / Math.max(0.1f, distToSun) * 2.0f;
            float sunAvoidX = midX * sunAvoidFactor;
            float sunAvoidZ = midZ * sunAvoidFactor;
            
            path.p1.set(
                cameraOffset.x + (endX - cameraOffset.x) * 0.2f - sunAvoidX,
                cameraOffset.y + pathHeight * 0.85f,
                cameraOffset.z + (endZ - cameraOffset.z) * 0.2f - sunAvoidZ
            );
            path.p2.set(
                cameraOffset.x + (endX - cameraOffset.x) * 0.7f - sunAvoidX * 0.5f,
                cameraOffset.y + pathHeight * 0.75f,
                cameraOffset.z + (endZ - cameraOffset.z) * 0.7f - sunAvoidZ * 0.5f
            );
        }
        
        return path;
    }
    
    private void updateCameraFollowing(Vector3f star, Vector3f prevStar, Vector3f planetPos,
                                   TransitionStage sd, int planetIndex, float t, float rawT, NavigationState state){
        Vector3f dir = new Vector3f(star).sub(prevStar);
        if (dir.length() > 0.001f) dir.normalize();
        else dir.set(planetPos).sub(star).normalize();
        
        float startDist = (currentStage == 1) ? 12.0f : stages[Math.max(0, planetIndex - 1)].camDist * 1.5f;
        float camDist = lerp(startDist, sd.camDist * 1.8f, t);
        
        cameraX = star.x - dir.x * camDist;
        cameraY = star.y - dir.y * camDist + camDist * 0.4f;
        cameraZ = star.z - dir.z * camDist;
        
        float lookAheadDist = lerp(5.0f, 2.0f, t);
        float smoothFactor = smoothstep(0.7f, 1.0f, rawT);
        state.lookAtX = lerp(star.x + dir.x * lookAheadDist, planetPos.x, smoothFactor);
        state.lookAtY = lerp(star.y + dir.y * lookAheadDist, planetPos.y, smoothFactor);
        state.lookAtZ = lerp(star.z + dir.z * lookAheadDist, planetPos.z, smoothFactor);
    }  
    
    private Vector3f getPlanetPosition(int planetIndex, float gt) {
        CelestialBody p = planets[planetIndex];
        float angle = gt * p.orbitSpeed;
        return new Vector3f((float)Math.sin(angle) * p.orbitRadius, 0.0f, (float)Math.cos(angle) * p.orbitRadius);
    }
    
    private static Vector3f evaluateCubicBezier(PathData path, float t, Vector3f dest) {
        float u = 1.0f - t;
        float b0 = u*u*u, b1 = 3*u*u*t, b2 = 3*u*t*t, b3 = t*t*t;
        if (dest == null) dest = new Vector3f();
        dest.x = b0 * path.p0.x + b1 * path.p1.x + b2 * path.p2.x + b3 * path.p3.x;
        dest.y = b0 * path.p0.y + b1 * path.p1.y + b2 * path.p2.y + b3 * path.p3.y;
        dest.z = b0 * path.p0.z + b1 * path.p1.z + b2 * path.p2.z + b3 * path.p3.z;
        return dest;
    }
    
    private void jumpToPlanet(int planetNum) {
        if (planetNum == 0) {
            currentStage = 0;
            cameraX = cameraY = 0.0f; cameraZ = 8.0f;
            transitionComplete = true;
        } else if (planetNum >= 1 && planetNum <= 9) {
            int planetIndex = planetNum - 1;
            currentStage = planetIndex * 2 + 2;
            float gt = (float)(System.currentTimeMillis() / 100.0);
            Vector3f planetPos = getPlanetPosition(planetIndex, gt);
            TransitionStage sd = stages[planetIndex];
            cameraOffset.set(planetPos.x + sd.endOffset, planetPos.y + sd.endOffset * 0.5f, planetPos.z + sd.endOffset);
            stageStartTime = System.currentTimeMillis();
            transitionComplete = true;
        }
    }
    
    private void setupVertexAttributes(GL4 gl) {
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[0]);
        gl.glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[1]);
        gl.glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(1);
    }
    
    private static float easeInOutCubic(float t) {
        return t < 0.5f ? 4.0f * t * t * t : 1.0f - (float)Math.pow(-2.0f * t + 2.0f, 3.0f) / 2.0f;
    }
    
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }   
    
    public void keyPressed(KeyEvent e) {
        int keyCode = e.getKeyCode();
        //T --> Toggle Between Top-Down and Fly-By 
        if (keyCode == KeyEvent.VK_T) {
            topDownMode = !topDownMode;
            projectionNeedsUpdate = true;
            
        //S --> Switch Between Realistic and Scaled View    
        } else if (keyCode == KeyEvent.VK_S && topDownMode) {
            scaledViewMode = !scaledViewMode;
            projectionNeedsUpdate = true;
            
        //Right Arrow --> Cycle Through Fly-By    
        } else if (keyCode == KeyEvent.VK_RIGHT && !topDownMode) {
            if (transitionComplete && currentStage % 2 == 0 && currentStage < 18) {
                currentStage++;
                stageStartTime = System.currentTimeMillis();
                transitionComplete = false;
            }
            
        //0-9 --> Jump to Specific Planets    
        } else if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9 && !topDownMode) {
            jumpToPlanet(keyCode - KeyEvent.VK_0);
        }
    }
    
    public void keyReleased(KeyEvent e) {}
    public void keyTyped(KeyEvent e) {}
    
    public static void main(String[] args) { new Code(); }
    public void dispose(GLAutoDrawable drawable) {}
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        projectionNeedsUpdate = true;
    }
    
    
}