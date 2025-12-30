# 🌀 TSFG: Turung Software Frame Generator v1.1 🌀

### "Rendimiento extremo donde otros solo ven lag" 🛠️📱

**TSFG** no es el típico "Game Optimizer" de cartón que solo borra la caché. Es un motor de **post-procesamiento dinámico** inyectado directamente en el flujo de renderizado de Android. Está diseñado para exprimir frames de donde no los hay, usando matemáticas puras y gestión de memoria manual para que hasta el teléfono más humilde corra como un gama alta. 🧠⚡

---

## 🏗️ ARQUITECTURA DE BAJO NIVEL (EL "JUGO")

### 1. El Motor de Captura: `MediaProjection` + `ImageReader` 📸
El sistema captura el framebuffer del sistema en tiempo real. Aquí es donde empieza la magia:
* **Configuración del PixelFormat:** Se usa `PixelFormat.RGBA_8888` para una precisión absoluta bit a bit.
* **Zero-Allocation Flow:** No se crean objetos nuevos en cada frame. Se extrae el `ByteBuffer` y se vuelca en un `byte[]` pre-asignado en el `resourceCache`. Esto mantiene al **Garbage Collector** de Android durmiendo, evitando los famosos tirones (stuttering). ♻️
* **Advertencia Técnica ⚠️:** El acceso al buffer es crítico. El código usa un `synchronized` estricto porque si el `ImageReader` se cierra mientras el hilo de procesamiento está leyendo, el sistema colapsaría. Seguridad ante todo.



### 2. El Santo Grial: Algoritmo SPME (v1.1) 🎯
Este es el corazón de la aplicación: el **Sparse Point Motion Estimation**. 
* **Muestreo Inteligente:** En lugar de recorrer millones de píxeles, el algoritmo salta a **8 puntos de interés estratégicos** (esquinas, centros de bordes y centro de pantalla).
* **Canal Verde (G) Hack:** `aIdx = y * stride + x * 4 + 1`. Ese `+1` es la clave. El canal verde contiene la mayor parte de la información de brillo. Analizar solo este canal permite detectar movimiento global con un ahorro del 75% en potencia de CPU. 🟢
* **Umbral de Ruido (SAD < 30):** Si la diferencia es mínima, se considera ruido digital o estático y se ignora. Esto evita que la imagen "baile" innecesariamente. 🤫

### 3. Generación de Frames: Extrapolación de Movimiento 📈
Cuando activas el modo **SIFg v1.1**:
* Si el algoritmo detecta un desplazamiento de, por ejemplo, `+2 píxeles`, el motor genera frames artificiales desplazados matemáticamente. 
* **Resultado:** El ojo humano percibe una transición suave (fluidez) en lugar de saltos bruscos. ¡Matemáticas aplicadas al gaming! ✨

---

## 🛠️ FUNCIONES DESTACADAS (DETALLES TÉCNICOS)

### 🌑 El "Blackout" de SurfaceView
Se fuerza el fondo a negro sólido: `surfaceView.setBackgroundColor(Color.BLACK);`
Esto parece simple, pero es vital: evita que el sistema operativo intente mezclar (blending) la transparencia del overlay con las ventanas de abajo, ahorrando ciclos preciosos al `SurfaceFlinger`. ⚡

### 🎮 Touch Forwarding Pro (Inyección de Gestos)
Como la app pone una "capa" sobre la pantalla, los toques se bloquearían. TSFG usa un **AccessibilityService** avanzado:
* **Precisión Quirúrgica:** Usa `dispatchGesture` para replicar `ACTION_DOWN`, `ACTION_MOVE` y `ACTION_UP` con los mismos tiempos originales. El juego no nota la diferencia. 🕹️
* **Prioridad Urgente:** El hilo táctil corre con `THREAD_PRIORITY_URGENT_DISPLAY`. La latencia de respuesta es casi inexistente.

### 🎨 Modos de Color y Resolución
* **Escalado Dinámico:** Puedes bajar la resolución al 25%, 50% o 75%. El juego renderiza menos píxeles (vuela) y TSFG los estira de nuevo con un algoritmo de **Nearest Neighbor** ultra-rápido.
* **Modos de 8-bit y 256 colores:** Ideales para exprimir el ancho de banda de la RAM en teléfonos con poca memoria. 👾

---

## ⚠️ ALERTAS Y ADVERTENCIAS ⚠️

* **ALERTA DE CALOR 🔥:** Procesar frames en tiempo real es una tarea intensiva. Si notas que el teléfono quema, reduce el número de frames generados.
* **SALVAVIDAS DE BATERÍA 🔋:** El sistema monitoriza tu carga. Si baja del **15%**, el modo SIFg se apaga automáticamente para que no te quedes sin teléfono en mitad de la partida.
* **SERVICIO DE ACCESIBILIDAD 🚨:** Si desactivas el permiso de accesibilidad mientras la app corre, **perderás el control táctil**. ¡Asegúrate de tenerlo siempre activo!



---

## 📊 MONITOREO EN TIEMPO REAL 📈

TSFG incluye un panel de estadísticas que te muestra:
* **FPS Actuales:** Calculados mediante `AtomicInteger` para no bloquear el hilo de renderizado.
* **Promedio Histórico:** Se guarda en `SharedPreferences` para que sepas qué configuración le sienta mejor a tu dispositivo.

---

## 💳 CRÉDITOS Y AUTORÍA

Este proyecto es fruto de la ingeniería de optimización extrema y el deseo de llevar el gaming fluido a todos los rincones.

* **Desarrollador Principal:** [LexusYTG](https://github.com/LexusYTG) 👨‍💻
* **Proyecto:** TSFG (Turung Software Frame Generator) 🌀
* **Algoritmo de Movimiento:** SPME v1.1 Propiedad de Turung Software. 🛠️

---
*Hecho con ☕ y muchas ganas de ver esos 30 FPS en cualquier "poronga" de teléfono.*
