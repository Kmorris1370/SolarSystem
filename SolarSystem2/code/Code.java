package code;

import java.io.*;
import java.nio.*;
import java.lang.Math;
import javax.swing.*;
import static com.jogamp.opengl.GL4.*;
import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.util.*;
import com.jogamp.common.nio.Buffers;
import org.joml.*;
import java.awt.event.*;


public class Code extends JFrame implements GLEventListener, KeyListener {
    private GLJPanel myPanel;
    private Animator animator;
    private int renderingProgram;
    private int vao[] = new int[1];
    private int vbo[] = new int[15];
    private Sphere mySphere;
    private int numSphereVerts, numOrbitVerts, numSquareVerts;
    
    //Camera & Projection
    private float cameraX, cameraY, cameraZ;
    private float aspect;
    private int mvLoc, pLoc, colorLoc;
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
    private float prevCameraX, prevCameraY, prevCameraZ;
    
    //etc. 
    private CelestialBody[] planets;
    private TransitionStage[] stages;
    private int sunTexture, shootingStarTexture, skydomeTexture;
    
    //Transition Variables
    private static final float APPROACH_DURATION = 6.0f;  
    private static final float ORBIT_DURATION = 4.0f;     
    private static final float ORBIT_RADIUS = 1.2f;       
    
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
        
        /*//Earth's Moon
        CelestialBody withMoon(float moonOrbitRadius, float moonSize, int moonTexture) {
            this.moonOrbitRadii = new float[]{moonOrbitRadius};
            this.moonSizes = new float[]{moonSize};
            this.moonTextures = new int[]{moonTexture};
            this.moonSpeeds = new float[]{0.3f};
            return this;
        }*/
        
        
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
        setSize(1000, 1000);
        myPanel = new GLJPanel();
        myPanel.addGLEventListener(this);
        myPanel.addKeyListener(this);
        myPanel.setFocusable(true);
        myPanel.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { myPanel.requestFocus(); }
        });
        this.add(myPanel);
        this.setVisible(true);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        myPanel.requestFocus();
        startTime = stageStartTime = System.currentTimeMillis();       
        animator = new Animator(myPanel);
        animator.start();
    }
    
    //(name, orbitRadius, size, rotationSpeed, orbitSpeed)
    private void initializePlanets() {
        planets = new CelestialBody[] {
            new CelestialBody("Mercury", 40.0f, 0.6f, 1.0f, 0.4f),   
            new CelestialBody("Venus", 80.0f, 0.95f, 0.8f, 0.3f),    
            new CelestialBody("Earth", 107.0f, 1.5f, 0.6f, 0.2f)      
                .withMoons(
                    new float[]{1.0f},
                    new float[]{0.5f},  
                    new int[]{0},
                    new float[]{0.3f}
                ),
            new CelestialBody("Mars", 150.0f, 0.85f, 0.7f, 0.1f),    
            new CelestialBody("Jupiter", 520.0f, 4.0f, 0.4f, 0.05f)   
                .withMoons(
                    new float[]{2.0f, 3.0f, 4.0f, 5.5f},
                    new float[]{0.29f, 0.24f, 0.41f, 0.38f},  
                    new int[]{0, 0, 0, 0},
                    new float[]{0.8f, 0.5f, 0.35f, 0.2f}
                ),
            new CelestialBody("Saturn", 950.0f, 3.0f, 0.3f, 0.03f),  
            new CelestialBody("Uranus", 1920.0f, 2.4f, 0.25f, 0.02f), 
            new CelestialBody("Neptune", 3000.0f, 2.0f, 0.2f, 0.01f),
            new CelestialBody("Pluto", 3950.0f, 0.4f, 0.15f, 0.008f) 
        };
    }
    
    //(endOffset, cp1, cp2, camDist)
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
    
     //Top-Down Mode
     private void displayTopDown(GL4 gl, float gt) {
        if (projectionNeedsUpdate) {
            aspect = (float) myPanel.getWidth() / (float) myPanel.getHeight();
            float viewSize = scaledViewMode ? 100.0f : 450.0f;
            pMat.identity().setOrtho(-viewSize * aspect, viewSize * aspect, -viewSize, viewSize, 0.1f, 5000.0f);
            gl.glUniformMatrix4fv(pLoc, 1, false, pMat.get(vals));
            projectionNeedsUpdate = false;
        }
        
        vMat.identity().lookAt(0.0f, 500.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
        mvStack.pushMatrix();
        mvStack.mul(vMat);
        
        renderSkydome(gl);
        
        setupVertexAttributes(gl);
        
        if (scaledViewMode) {
            renderTopDownView(gl, gt, true);
        } else {
            renderTopDownView(gl, gt, false);
        }
        
        mvStack.popMatrix();
    }
    
    //Navagation Mode
    private void displayNavigation(GL4 gl, float tf, float gt) {
        if (projectionNeedsUpdate) {
            aspect = (float) myPanel.getWidth() / (float) myPanel.getHeight();
            pMat.identity().setPerspective((float) Math.toRadians(60.0f), aspect, 0.1f, 5000.0f);
            gl.glUniformMatrix4fv(pLoc, 1, false, pMat.get(vals));
            projectionNeedsUpdate = false;
        }
        
        NavigationState state = calculateNavigationState(tf, gt);
        
        vMat.identity().lookAt(cameraX, cameraY, cameraZ, state.lookAtX, state.lookAtY, state.lookAtZ, 0, 1, 0);
        mvStack.pushMatrix();
        mvStack.mul(vMat);
        setupVertexAttributes(gl);
        
        renderSkydome(gl);
        
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
        colorLoc = gl.glGetUniformLocation(renderingProgram, "color");
        int texLoc = gl.glGetUniformLocation(renderingProgram, "samp");
        if (texLoc >= 0) gl.glUniform1i(texLoc, 0);
        
        gl.glEnable(GL_DEPTH_TEST);
        gl.glDepthFunc(GL_LEQUAL);
        gl.glEnable(GL_CULL_FACE);
        gl.glFrontFace(GL_CCW);
        
        aspect = (float) myPanel.getWidth() / (float) myPanel.getHeight();
        pMat.identity().setPerspective((float) Math.toRadians(60.0f), aspect, 0.1f, 5000.0f);
        
        startTime = System.currentTimeMillis();
        
        initializePlanets();
        initializeStages();
        setupVertices();
        setupOrbitLines();
        loadTextures();
        
        cameraX = 0.0f; cameraY = 0.0f; cameraZ = 8.0f;
        prevCameraX = cameraX; prevCameraY = cameraY; prevCameraZ = cameraZ;
    }

    
    private void loadTextures() {
        sunTexture = Utils.loadTexture("sun.jpg");
        shootingStarTexture = Utils.loadTexture("star.png");  
        skydomeTexture = Utils.loadTexture("skydome.png");
        
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
    
    //Sphere Vertices
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
        
        // Square Vertices
        float[] squareVertices = {
            // First triangle
            -0.5f, -0.5f, 0.0f,  // Bottom left
             0.5f, -0.5f, 0.0f,  // Bottom right
             0.5f,  0.5f, 0.0f,  // Top right
            // Second triangle
            -0.5f, -0.5f, 0.0f,  // Bottom left
             0.5f,  0.5f, 0.0f,  // Top right
            -0.5f,  0.5f, 0.0f   // Top left
        };
        
        float[] squareTexCoords = {
            // First triangle
            0.0f, 0.0f,  // Bottom left
            1.0f, 0.0f,  // Bottom right
            1.0f, 1.0f,  // Top right
            // Second triangle
            0.0f, 0.0f,  // Bottom left
            1.0f, 1.0f,  // Top right
            0.0f, 1.0f   // Top left
        };
        
        numSquareVerts = 6;
        
        gl.glGenVertexArrays(vao.length, vao, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glGenBuffers(15, vbo, 0);
        
        //Sphere vertices
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferData(GL_ARRAY_BUFFER, pvalues.length*4, Buffers.newDirectFloatBuffer(pvalues), GL_STATIC_DRAW);
        
        //Sphere Texture
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[1]);
        gl.glBufferData(GL_ARRAY_BUFFER, tvalues.length*4, Buffers.newDirectFloatBuffer(tvalues), GL_STATIC_DRAW);
        
        //Square Vertices
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[2]);
        gl.glBufferData(GL_ARRAY_BUFFER, squareVertices.length*4, Buffers.newDirectFloatBuffer(squareVertices), GL_STATIC_DRAW);
        
        //Square Texture
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[3]);
        gl.glBufferData(GL_ARRAY_BUFFER, squareTexCoords.length*4, Buffers.newDirectFloatBuffer(squareTexCoords), GL_STATIC_DRAW);
    }
    
    //Draw orbit lines in top-down mode
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
    
    
    private void renderSkydome(GL4 gl) {
        gl.glDepthMask(false);
        
        gl.glDisable(GL_CULL_FACE);
        
        mvStack.pushMatrix();
        mvStack.translate(0, 0, 0);
        mvStack.scale(4800.0f, 4800.0f, 4800.0f);
        
        gl.glActiveTexture(GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_2D, skydomeTexture);
        gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
        gl.glDrawArrays(GL_TRIANGLES, 0, numSphereVerts);
        
        mvStack.popMatrix();
        
        gl.glDepthMask(true);
        gl.glEnable(GL_CULL_FACE);
    }
    
    private void renderVisibleBodies(GL4 gl, float gt) {
        for (int i = 0; i < planets.length; i++) {
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
    
    private void renderTopDownView(GL4 gl, float gt, boolean scaled) {
        float[] planetSizes, orbitRadii;
        float orbitScale = 1.0f, moonOrbitScale = 1.5f;
        float sunSize;
        
        if (scaled) {
            planetSizes = new float[]{1.5f, 2.0f, 2.2f, 1.8f, 5.0f, 4.5f, 3.5f, 3.5f, 1.2f};
            orbitRadii = new float[]{8.0f, 12.0f, 16.0f, 20.0f, 30.0f, 40.0f, 50.0f, 60.0f, 70.0f};
            sunSize = 4.0f;
        } else {
            
            planetSizes = new float[]{0.2f, 0.48f, 0.5f, 0.27f, 5.5f, 4.6f, 2.0f, 1.95f, 0.1f};
            orbitRadii = null;
            orbitScale = 0.1f;
            moonOrbitScale = 15.0f;
            sunSize = 5.0f;
        }
        
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
        
        //Calculate direction 
        float dirX = prevX - starX;
        float dirY = prevY - starY;
        float dirZ = prevZ - starZ;
        float dirLen = (float)Math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ);
        if (dirLen > 0.001f) { dirX /= dirLen; dirY /= dirLen; dirZ /= dirLen; }
        
        //Calculate rotation 
        float angleY = (float)Math.atan2(dirX, dirZ);
        float angleX = (float)Math.asin(-dirY);
        
        gl.glActiveTexture(GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_2D, shootingStarTexture);
        
        gl.glEnable(GL_BLEND);
        gl.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        
        //Setup Square Vertex Attributes
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[2]);
        gl.glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glBindBuffer(GL_ARRAY_BUFFER, vbo[3]);
        gl.glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(1);
        
        //Calculate Camera Direction 
        float camDirX = cameraX - starX;
        float camDirY = cameraY - starY;
        float camDirZ = cameraZ - starZ;
        float camDirLen = (float)Math.sqrt(camDirX*camDirX + camDirY*camDirY + camDirZ*camDirZ);
        if (camDirLen > 0.001f) { camDirX /= camDirLen; camDirY /= camDirLen; camDirZ /= camDirLen; }
        
        float billboardAngleY = (float)Math.atan2(camDirX, camDirZ);
        float billboardAngleX = (float)Math.asin(-camDirY);
        
        mvStack.pushMatrix();
        mvStack.rotate(billboardAngleY, 0.0f, 1.0f, 0.0f);
        mvStack.rotate(billboardAngleX, 1.0f, 0.0f, 0.0f);
        mvStack.scale(0.3f, 0.3f, 0.3f);
        gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
        gl.glDrawArrays(GL_TRIANGLES, 0, numSquareVerts);
        mvStack.popMatrix();
        
        //Trail 
        int trailSegments = 15;
        for (int i = 1; i <= trailSegments; i++) {
            float t = (float)i / trailSegments;          
            float distOffset = i * 0.15f;           
            float baseSize = 0.2f * (1.0f - t * t);          
            if (baseSize < 0.001f) continue;
            
            mvStack.pushMatrix();
            mvStack.translate(dirX * distOffset, dirY * distOffset, dirZ * distOffset);
            mvStack.rotate(billboardAngleY, 0.0f, 1.0f, 0.0f);
            mvStack.rotate(billboardAngleX, 1.0f, 0.0f, 0.0f);
            mvStack.scale(baseSize, baseSize, baseSize);
            
            gl.glUniformMatrix4fv(mvLoc, 1, false, mvStack.get(vals));
            gl.glDrawArrays(GL_TRIANGLES, 0, numSquareVerts);
            mvStack.popMatrix();
        }
        
        gl.glDepthMask(true);
        gl.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        mvStack.popMatrix();
    }
    
    private NavigationState calculateNavigationState(float tf, float gt) {
        NavigationState state = new NavigationState();
        
        if (currentStage == 0) {
            cameraX = 0f; cameraY = 0f; cameraZ = 8.0f;
            prevCameraX = cameraX; prevCameraY = cameraY; prevCameraZ = cameraZ;
            state.lookAtX = 0f; state.lookAtY = 0f; state.lookAtZ = 0f;
            transitionComplete = true;
            state.drawStar = false;
            
        } else if (currentStage % 2 == 1) {
            // Odd stages: Approach transition
            calculateApproachPhase(tf, gt, state);
        } else {
        
            // Even stages: Orbit and stop
            calculateOrbitPhase(tf, gt, state);
        }
        
        return state;
    }
    
    private void calculateApproachPhase(float tf, float gt, NavigationState state) {
        int planetIndex = (currentStage - 1) / 2;  
        Vector3f targetPlanetPos = getPlanetPosition(planetIndex, gt);
        
        float progress = Math.min(tf / APPROACH_DURATION, 1.0f);
        float t = easeInOutCubic(progress);
        
        Vector3f startPos = new Vector3f(prevCameraX, prevCameraY, prevCameraZ);
        
        float orbitDist = ORBIT_RADIUS + planets[planetIndex].size;
        Vector3f endPos = new Vector3f(
            targetPlanetPos.x,
            targetPlanetPos.y,
            targetPlanetPos.z + orbitDist
        );
        
        Vector3f currentPlanetPos = null;
        if (currentStage > 1) {
            int currentPlanetIndex = ((currentStage - 1) / 2) - 1;  
            if (currentPlanetIndex >= 0) {
                currentPlanetPos = getPlanetPosition(currentPlanetIndex, gt);
            }
        }
        
        //Create Bezier path
        PathData path = new PathData();
        path.p0.set(startPos);
        path.p3.set(endPos);
        
        //Calculate direction from start to target
        Vector3f toTarget = new Vector3f(targetPlanetPos).sub(startPos);
        float totalDistance = toTarget.length();
        toTarget.normalize();
        
        boolean nearCurrentPlanet = false;
        Vector3f toCurrent = new Vector3f();
        float distToCurrent = 0;
        
        if (currentPlanetPos != null) {
            toCurrent = new Vector3f(currentPlanetPos).sub(startPos);
            distToCurrent = toCurrent.length();
            nearCurrentPlanet = distToCurrent < 15.0f;  
        }
        
        if (nearCurrentPlanet) {
            toCurrent.normalize();
            Vector3f perpendicular = new Vector3f(toCurrent).cross(0, 1, 0).normalize();
            float arcRadius = distToCurrent + 8.0f;  
            
            //Control Point 1
            path.p1.set(startPos)
                .add(new Vector3f(perpendicular).mul(arcRadius))  
                .add(new Vector3f(0, totalDistance * 0.15f, 0)); 
            
            //Control Point 2
            path.p2.set(path.p1)
                .add(new Vector3f(toTarget).mul(totalDistance * 0.4f))  
                .add(new Vector3f(0, totalDistance * 0.05f, 0));        
                
        } else {
            //Control Point 1
            path.p1.set(startPos)
                .add(new Vector3f(toTarget).mul(totalDistance * 0.33f))
                .add(new Vector3f(0, totalDistance * 0.1f, 0));
            
            //Control Point 2
            path.p2.set(endPos)
                .sub(new Vector3f(toTarget).mul(totalDistance * 0.33f))
                .add(new Vector3f(0, totalDistance * 0.05f, 0));
        }
        
        // Calculate Star Position 
        Vector3f starPos = evaluateCubicBezier(path, t, null);
        Vector3f prevStarPos = evaluateCubicBezier(path, Math.max(t - 0.02f, 0), new Vector3f());
        
        state.starX = starPos.x;
        state.starY = starPos.y;
        state.starZ = starPos.z;
        state.prevX = prevStarPos.x;
        state.prevY = prevStarPos.y;
        state.prevZ = prevStarPos.z;
        
        //Calculate direction 
        Vector3f direction = new Vector3f(starPos).sub(prevStarPos).normalize();
        
        //Position camera
        float baseCamDist = 12.0f + planets[planetIndex].size * 10.0f;
        float camHeight = baseCamDist * 0.7f;
        
        cameraX = starPos.x - direction.x * baseCamDist;
        cameraY = starPos.y + camHeight;
        cameraZ = starPos.z - direction.z * baseCamDist;
        state.lookAtX = targetPlanetPos.x;
        state.lookAtY = targetPlanetPos.y;
        state.lookAtZ = targetPlanetPos.z;
        
        state.drawStar = true;
        
        if (progress >= 1.0f) {
            prevCameraX = cameraX;
            prevCameraY = cameraY;
            prevCameraZ = cameraZ;
            
            currentStage++;
            stageStartTime = System.currentTimeMillis();
            transitionComplete = false;
        }
    }
    
    private void calculateOrbitPhase(float tf, float gt, NavigationState state) {
        int planetIndex = (currentStage - 2) / 2;
        Vector3f planetPos = getPlanetPosition(planetIndex, gt);
        
        float orbitDist = ORBIT_RADIUS + planets[planetIndex].size;
        float totalTime = ORBIT_DURATION;
        float cameraTransitionTime = 0.8f; 
        
        if (tf < totalTime) {
            // Orbiting phase 
            float orbitProgress = tf / totalTime;
            float angle = orbitProgress * (float)Math.PI * 4.0f; 
            
            //Star orbits around planet
            state.starX = planetPos.x + orbitDist * (float)Math.sin(angle);
            state.starY = planetPos.y + orbitDist * 0.2f * (float)Math.sin(angle * 2.0f);
            state.starZ = planetPos.z + orbitDist * (float)Math.cos(angle);
            
            //Previous position for trail
            float prevAngle = angle - 0.15f;
            state.prevX = planetPos.x + orbitDist * (float)Math.sin(prevAngle);
            state.prevY = planetPos.y + orbitDist * 0.2f * (float)Math.sin(prevAngle * 2.0f);
            state.prevZ = planetPos.z + orbitDist * (float)Math.cos(prevAngle);
            
            //Calculate target camera position
            float camDist = orbitDist + 3.0f + planets[planetIndex].size * 3.0f;
            float camAngle = angle - (float)Math.PI * 0.2f;
            float targetCamX = planetPos.x + camDist * (float)Math.sin(camAngle);
            float targetCamY = planetPos.y + camDist * 0.35f;
            float targetCamZ = planetPos.z + camDist * (float)Math.cos(camAngle);
            
            //Camera transition ]
            if (tf < cameraTransitionTime) {
                float camTransition = smoothstep(0.0f, 1.0f, tf / cameraTransitionTime);
                cameraX = lerp(prevCameraX, targetCamX, camTransition);
                cameraY = lerp(prevCameraY, targetCamY, camTransition);
                cameraZ = lerp(prevCameraZ, targetCamZ, camTransition);
            } else {
                cameraX = targetCamX;
                cameraY = targetCamY;
                cameraZ = targetCamZ;
            }
            
            state.lookAtX = planetPos.x;
            state.lookAtY = planetPos.y;
            state.lookAtZ = planetPos.z;
            
            state.drawStar = true;
            transitionComplete = false;
            
        } else {
            //In front of planet 
            state.starX = planetPos.x;
            state.starY = planetPos.y;
            state.starZ = planetPos.z + orbitDist;
            
            state.prevX = state.starX;
            state.prevY = state.starY;
            state.prevZ = state.starZ + 0.1f;
            
            //Camera position
            float viewDist = orbitDist + 2.0f + planets[planetIndex].size * 2.0f;
            cameraX = planetPos.x;
            cameraY = planetPos.y + viewDist * 0.3f;
            cameraZ = planetPos.z + viewDist;
            
            state.lookAtX = planetPos.x;
            state.lookAtY = planetPos.y;
            state.lookAtZ = planetPos.z;
            
            state.drawStar = false;  
            transitionComplete = true;
        }
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
            cameraX = 0.0f; cameraY = 0.0f; cameraZ = 8.0f;
            prevCameraX = cameraX; prevCameraY = cameraY; prevCameraZ = cameraZ;
            transitionComplete = true;
        } else if (planetNum >= 1 && planetNum <= 9) {
            int planetIndex = planetNum - 1;
            currentStage = planetIndex * 2 + 2;
            stageStartTime = System.currentTimeMillis() - (long)(ORBIT_DURATION * 1000.0f + 100);
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