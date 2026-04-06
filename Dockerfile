FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    tesseract-ocr \
    python3 python3-pip \
    && pip3 install --break-system-packages openai-whisper \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY build/libs/video*.jar /app/app.jar

ENV OPENROUTER_API_KEY=""
VOLUME /app/samples/hls

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
