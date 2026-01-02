# 🚀 Frame Generator Pro v1.0

**Desarrollador:** LexusYTG  
**Licencia:** GNU AGPLv3  
**Base técnica:** Java 7 para Android  
**Repositorio:** [github.com/LexusYTG/TSFG](https://github.com/LexusYTG/TSFG)  

Esta app es una herramienta simple para escalar y generar frames en pantalla en dispositivos Android. No es mágica, solo optimiza lo que ya tienes.

## 🔍 ¿Qué hace?
- Captura la pantalla a baja resolución.  
- Escala los frames a resolución completa.  
- Genera frames intermedios para multiplicar FPS (hasta 5x).  
- Muestra todo en un overlay opaco para un renderizado fluido.  
- Ajusta calidad de color y modos para balancear rendimiento vs. visuales.

## 🛠️ ¿Cómo lo logra?
- Usa MediaProjection para capturar pantalla.  
- Escala con nearest-neighbor rápido (sin filtros fancy).  
- Genera frames con algoritmos propietarios: SIFg v1.0 (de un solo frame, sin ghosting) y v1.1 (interpolación rápida, anti-stutter).  
- Renderiza en SurfaceView con buffers directos para baja latencia.  
- Maneja batería baja cambiando a modo rendimiento.

## ⚡ ¿Por qué funciona?
- Reduce carga capturando a 25-75% de resolución original.  
- Genera frames extras basados en movimiento simple, no AI pesada.  
- Overlay hardware-accelerado evita lags del sistema.  
- Optimizaciones en threads y buffers mantienen FPS estables en hardware modesto.

## 🔄 ¿Cómo funciona?
1. Inicia la app y concede permisos (captura y overlay).  
2. Elige modo: Rendimiento (directo) o SIFg (generación).  
3. Configura resolución, frames a generar (1-4) y calidad color (full, 8-bit, 256 colores).  
4. Presiona "INICIAR CAPTURA": esconde UI y muestra overlay.  
5. Detén con back o notificación. Monitorea FPS/drops en tiempo real.

## 📝 Recomendaciones de uso
- Usa en dispositivos mid-range o low-end para juegos.  
- Empieza con resolución 50% y 1 frame generado.  
- Monitorea batería: activa modo rendimiento si baja a <15%.  
- Reinicia app si errores de captura (raro, pero pasa).  
- Prueba en orientación portrait/landscape; ajusta auto.

## ✅ Casos donde puede ayudar
- Juegos con FPS bajos: multiplica frames para smoother gameplay.  
- Apps con stutter (scrolling, videos): reduce lags interpolando.  
- Dispositivos viejos: baja resolución libera CPU/GPU.  
- Modo retro: 8-bit/256 colores para estética vintage en emuladores.

## ❌ Casos donde no
- Hardware top-tier: no notarás diferencia, quizás overhead extra.  
- Apps con input preciso (e.g., shooters): overlay no touchable, usa con cuidado.  
- Batería crítica: generación consume más, mejor apagar.  
- Contenido estático (e.g., lectura): generación innecesaria, usa modo básico.

## 🎯 Cosas que puede hacer
- Multiplicar FPS reales hasta 5x sin ghosting.  
- Reducir calidad color para más velocidad.  
- Mostrar stats en vivo (FPS, frames, drops).  
- Auto-ajustar por batería o rotación.  
- Funcionar en background con notificación.

## 🚫 Cosas que no puede hacer
- No genera frames perfectos: interpolación simple, no maneja escenas complejas.  
- No soporta multitarea real (overlay cubre todo).  
- No instala mods o hacks: solo overlay sobre pantalla.  
- No graba video: solo renderiza en vivo.  
- No funciona sin permisos o en Android <7 (API checks incluidos).

Si rompes algo, es bajo AGPLv3: contribuye o fork. ¡Prueba y ajusta! 💥
