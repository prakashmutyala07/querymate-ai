FROM mcr.microsoft.com/azure-databases/data-api-builder:latest

ENV ASPNETCORE_URLS=http://0.0.0.0:5001

COPY dab-config.json /App/dab-config.json

EXPOSE 5001

CMD ["--ConfigFileName", "/App/dab-config.json"]
