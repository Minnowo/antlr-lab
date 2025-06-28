# antlr4-lab

This is a hard fork of [antlr4-lab](https://github.com/antlr/antlr4-lab).

Antlr4-lab is A client/server for trying out and learning about ANTLR

## Building and launching server

Ubuntu with lab.antlr.org static IP

```bash
# needed to render the AST image
sudo apt install -y ghostscript # gets ps2pdf
sudo apt install -y pdf2svg

# git clone ...
cd antlr4-lab
mvn clean package

# run the server
java -cp ./target/antlr4-lab-0.4-SNAPSHOT-complete.jar org.antlr.v4.server.ANTLRHttpServer

# see http://localhost:8000
```

### Docker

```bash
cd antlr4-lab
docker build --tag antlr4-lab .

# run the image
docker run --rm -it -p 8000:8000 -v ./work:/app/work antlr-lab

# see http://localhost:8000
```

# Configuration

Grammars are served from `./work` by default, see [work-example](./work-example) for examples.
You can change this directory by using the `WORK_DIR` environment variable.


