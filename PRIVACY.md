## Política de Privacidad de KITIKAT

**Última actualización: 9 de junio de 2026**

KITIKAT es una aplicación de generación de fotogramas (interpolación) en tiempo real para Android. Esta política explica cómo manejamos tu información.

### 1. No recopilamos datos personales

KITIKAT **no recopila, almacena ni transmite ningún tipo de información personal o identificable**. La aplicación no requiere cuentas de usuario, no utiliza servidores externos, no incluye analíticas, ni publicidad, ni rastreadores.

### 2. Permisos y uso local

Para funcionar, KITIKAT necesita los siguientes permisos. Todo el procesamiento se realiza **exclusivamente en tu dispositivo**:

- **Captura de pantalla (MediaProjection)**: Necesaria para obtener los fotogramas de la pantalla de otras apps y aplicar interpolación. Las imágenes capturadas se procesan en tiempo real y se descartan inmediatamente después de generar el fotograma interpolado. **Nunca se guardan ni se envían fuera del dispositivo.**

- **Servicio de accesibilidad (AccessibilityService)**: Opcional. Se utiliza para reenviar los toques a la app original después de la interpolación. No registra pulsaciones ni contenido de la pantalla. Solo se activa si el usuario lo concede explícitamente.

- **Ventana flotante (SYSTEM_ALERT_WINDOW)**: Para mostrar la salida de vídeo interpolada superpuesta a otras apps. No recopila datos.

- **Servicio en primer plano (FOREGROUND_SERVICE)**: Permite que la interpolación continúe mientras usas otras aplicaciones. No implica recolección de datos.

### 3. Almacenamiento local

KITIKAT no lee ni escribe archivos en tu almacenamiento externo (fotos, documentos, etc.). No guarda ninguna configuración fuera de la configuración estándar de Android.

### 4. Menores de edad

La aplicación puede ser utilizada por cualquier persona, pero no recopila ningún dato de nadie, incluidos menores.

### 5. Cambios en esta política

Si en futuras versiones se añade alguna funcionalidad que afecte a la privacidad (ej. opción de guardar logs locales), se actualizará esta política. El código es abierto y cualquier cambio puede verificarse en:  
https://github.com/LexusYTG/TSFG-KITIKAT-SCALER-

### 6. Contacto

Si tienes preguntas sobre privacidad, puedes abrir un issue en el repositorio oficial:  
https://github.com/LexusYTG/TSFG-KITIKAT-SCALER-/issues
