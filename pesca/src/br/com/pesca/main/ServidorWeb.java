package br.com.pesca.main;

import br.com.pesca.model.Pescador;
import br.com.pesca.model.Peixe;
import br.com.pesca.model.Raridade;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servidor web do jogo de pesca (multijogador).
 *
 * - Serve a pasta "web" (index.html, style.css, script.js)
 * - API: /api/entrar, /api/pescar, /api/descansar, /api/ranking
 * - Salva o progresso dos jogadores em arquivo (dados/jogadores.txt)
 *
 * Variáveis de ambiente (opcionais):
 *   PORT      -> porta do servidor (padrão 8080). Hospedagens definem sozinhas.
 *   DATA_DIR  -> pasta onde o progresso é salvo (padrão "dados").
 */
public class ServidorWeb {

    private static final Random random = new Random();
    private static final long INTERVALO_PESCA_MS = 1000;

    private static final Path ARQUIVO =
            Path.of(System.getenv().getOrDefault("DATA_DIR", "dados"), "jogadores.txt");

    // jogadores por nome (minúsculo) e sessões por token
    private static final Map<String, Pescador> jogadores = new ConcurrentHashMap<>();
    private static final Map<String, Pescador> sessoes = new ConcurrentHashMap<>();
    private static final Map<String, Long> ultimaPesca = new ConcurrentHashMap<>();

    private static final Peixe[] peixes = {
        new Peixe("Tilápia", Raridade.COMUM, 20),
        new Peixe("Carpa", Raridade.INCOMUM, 40),
        new Peixe("Salmão", Raridade.RARO, 80),
        new Peixe("Tubarão", Raridade.EPICO, 150),
        new Peixe("Megalodon", Raridade.LENDARIO, 400)
    };

    // chances (%) de cada peixe: COMUM, INCOMUM, RARO, EPICO, LENDARIO
    private static final int[][] PESOS = {
        {60, 28,  9,  2,  1},  // lançamento curto  (força < 35)
        {30, 30, 25, 12,  3},  // lançamento médio  (força < 70)
        { 5, 15, 30, 32, 18}   // lançamento longo  (força >= 70)
    };

    public static void main(String[] args) throws IOException {
        int porta = 8080;
        String portaEnv = System.getenv("PORT");
        if (portaEnv != null && !portaEnv.isBlank()) {
            porta = Integer.parseInt(portaEnv.trim());
        }

        // Procura a pasta "web" (abrindo o VS Code na pasta pesca ou na pasta acima)
        Path pastaWeb = Path.of("web");
        if (!Files.isDirectory(pastaWeb)) {
            pastaWeb = Path.of("pesca", "web");
        }
        final Path raiz = pastaWeb.toAbsolutePath().normalize();

        carregarDados();

        // InetSocketAddress(porta) escuta em todas as interfaces (aceita outros PCs)
        HttpServer server = HttpServer.create(new InetSocketAddress(porta), 0);

        server.createContext("/api/entrar", ex -> api(ex, "entrar"));
        server.createContext("/api/pescar", ex -> api(ex, "pescar"));
        server.createContext("/api/descansar", ex -> api(ex, "descansar"));
        server.createContext("/api/ranking", ServidorWeb::ranking);
        server.createContext("/", ex -> arquivo(ex, raiz));

        server.start();
        System.out.println("Jogo de pesca no ar: http://localhost:" + porta);
        System.out.println("Pasta web: " + raiz);
        System.out.println("Progresso salvo em: " + ARQUIVO.toAbsolutePath());
    }

    // ---------- Força e sorteio ----------

    private static int zona(int forca) {
        return forca < 35 ? 0 : (forca < 70 ? 1 : 2);
    }

    private static Peixe sortearPeixe(int forca) {
        int[] pesos = PESOS[zona(forca)];
        int total = 0;
        for (int w : pesos) total += w;
        int sorteio = random.nextInt(total);
        for (int i = 0; i < peixes.length; i++) {
            sorteio -= pesos[i];
            if (sorteio < 0) return peixes[i];
        }
        return peixes[0];
    }

    private static int lerForca(String s) {
        try {
            return Math.max(10, Math.min(100, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return 10;
        }
    }

    // ---------- API ----------

    private static void api(HttpExchange ex, String acao) throws IOException {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String json;

            if (acao.equals("entrar")) {
                String nome = limparNome(q.getOrDefault("nome", ""));
                if (nome.isEmpty()) {
                    enviar(ex, 400, "application/json", "{\"erro\":\"Digite um nome (letras e números)\"}");
                    return;
                }

                String chave = nome.toLowerCase(Locale.ROOT);
                boolean novo = !jogadores.containsKey(chave);
                Pescador p = jogadores.computeIfAbsent(chave, k -> new Pescador(nome));

                if (sessoes.size() > 20000) { // proteção contra encher a memória
                    sessoes.clear();
                    ultimaPesca.clear();
                }
                String token = UUID.randomUUID().toString();
                sessoes.put(token, p);
                if (novo) salvar();

                String estado;
                synchronized (p) { estado = estado(p); }
                json = "{\"token\":\"" + token + "\"," + estado + "}";

            } else {
                String token = q.getOrDefault("token", "");
                Pescador p = sessoes.get(token);
                if (p == null) {
                    enviar(ex, 401, "application/json", "{\"erro\":\"Sessão inválida. Entre de novo.\"}");
                    return;
                }

                if (acao.equals("pescar")) {
                    long agora = System.currentTimeMillis();
                    Long ultima = ultimaPesca.get(token);
                    if (ultima != null && agora - ultima < INTERVALO_PESCA_MS) {
                        enviar(ex, 429, "application/json", "{\"erro\":\"Calma! Espere um instante para pescar de novo.\"}");
                        return;
                    }
                    ultimaPesca.put(token, agora);

                    int forca = lerForca(q.get("forca"));

                    Peixe pescado = null;
                    String estado;
                    synchronized (p) {
                        forca = p.limitarForca(forca); // cansado não passa de 40
                        if (p.getEnergia() > 0) {
                            p.gastarEnergia(Pescador.custo(forca));
                            pescado = sortearPeixe(forca);
                            p.adicionarMoedas(pescado.getValor());
                            p.subirNivel();
                        }
                        estado = estado(p);
                    }

                    if (pescado == null) { // energia zerada
                        enviar(ex, 409, "application/json",
                            "{\"erro\":\"Sem energia! Descanse um pouco.\"," + estado + "}");
                        return;
                    }
                    salvar();

                    json = "{" + estado + ",\"forca\":" + forca + ",\"peixe\":{"
                        + "\"nome\":\"" + esc(pescado.getNome()) + "\","
                        + "\"raridade\":\"" + pescado.getRaridade() + "\","
                        + "\"valor\":" + pescado.getValor() + "}}";
                } else { // descansar
                    String estado;
                    synchronized (p) {
                        p.descansar();
                        estado = estado(p);
                    }
                    json = "{" + estado + ",\"mensagem\":\"Você descansou.\"}";
                }
            }

            enviar(ex, 200, "application/json", json);

        } catch (Exception e) {
            e.printStackTrace();
            enviar(ex, 500, "application/json", "{\"erro\":\"Erro interno\"}");
        }
    }

    /** Top 10 pescadores por moedas. */
    private static void ranking(HttpExchange ex) throws IOException {
        try {
            List<Pescador> lista = new ArrayList<>(jogadores.values());
            lista.sort((a, b) -> {
                int c = Integer.compare(b.getMoedas(), a.getMoedas());
                return c != 0 ? c : Integer.compare(b.getNivel(), a.getNivel());
            });

            StringBuilder sb = new StringBuilder("[");
            int total = Math.min(10, lista.size());
            for (int i = 0; i < total; i++) {
                Pescador p = lista.get(i);
                if (i > 0) sb.append(",");
                synchronized (p) { sb.append("{").append(estado(p)).append("}"); }
            }
            sb.append("]");

            ex.getResponseHeaders().set("Cache-Control", "no-store");
            enviar(ex, 200, "application/json", sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            enviar(ex, 500, "application/json", "[]");
        }
    }

    private static String estado(Pescador p) {
        return "\"nome\":\"" + esc(p.getNome()) + "\","
             + "\"nivel\":" + p.getNivel() + ","
             + "\"moedas\":" + p.getMoedas() + ","
             + "\"energia\":" + p.getEnergia() + ","
             + "\"cansado\":" + p.isCansado();
    }

    /** Só letras, números, espaço, _ . - (máx. 20). Evita nomes estranhos e HTML. */
    private static String limparNome(String bruto) {
        String s = bruto.replaceAll("[^\\p{L}\\p{N} _.-]", "").trim();
        if (s.length() > 20) s = s.substring(0, 20).trim();
        return s;
    }

    // ---------- Persistência (arquivo simples: nome;nivel;moedas) ----------

    private static synchronized void salvar() {
        try {
            Path pasta = ARQUIVO.toAbsolutePath().getParent();
            if (pasta != null) Files.createDirectories(pasta);

            List<String> linhas = new ArrayList<>();
            for (Pescador p : jogadores.values()) {
                synchronized (p) {
                    linhas.add(p.getNome() + ";" + p.getNivel() + ";" + p.getMoedas());
                }
            }
            Files.write(ARQUIVO, linhas, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Falha ao salvar progresso: " + e.getMessage());
        }
    }

    private static void carregarDados() {
        if (!Files.isRegularFile(ARQUIVO)) return;
        try {
            for (String linha : Files.readAllLines(ARQUIVO, StandardCharsets.UTF_8)) {
                String[] partes = linha.split(";");
                if (partes.length != 3) continue;
                try {
                    String nome = partes[0];
                    int nivel = Integer.parseInt(partes[1].trim());
                    int moedas = Integer.parseInt(partes[2].trim());
                    jogadores.put(nome.toLowerCase(Locale.ROOT), restaurar(nome, nivel, moedas));
                } catch (NumberFormatException ignorada) { }
            }
            System.out.println("Jogadores carregados: " + jogadores.size());
        } catch (IOException e) {
            System.out.println("Falha ao ler progresso: " + e.getMessage());
        }
    }

    /** Recria um Pescador com o nível e as moedas salvos. */
    private static Pescador restaurar(String nome, int nivel, int moedas) {
        Pescador p = new Pescador(nome);
        int falta = moedas - p.getMoedas();
        if (falta > 0) p.adicionarMoedas(falta);
        for (int i = 0; i < 100000 && p.getNivel() < nivel; i++) {
            p.subirNivel();
        }
        return p;
    }

    // ---------- Arquivos estáticos ----------

    private static void arquivo(HttpExchange ex, Path raiz) throws IOException {
        String caminho = ex.getRequestURI().getPath();
        if (caminho.equals("/")) caminho = "/index.html";

        Path arq = raiz.resolve(caminho.substring(1)).normalize();

        // impede acessar arquivos fora da pasta web
        if (!arq.startsWith(raiz) || !Files.isRegularFile(arq)) {
            enviar(ex, 404, "text/plain; charset=utf-8", "Não encontrado");
            return;
        }

        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        enviarBytes(ex, 200, tipo(arq.toString()), Files.readAllBytes(arq));
    }

    private static String tipo(String nome) {
        if (nome.endsWith(".html")) return "text/html; charset=utf-8";
        if (nome.endsWith(".css")) return "text/css; charset=utf-8";
        if (nome.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (nome.endsWith(".png")) return "image/png";
        if (nome.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ---------- Utilitários ----------

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k, v);
        }
        return m;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void enviar(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        enviarBytes(ex, status, tipo.contains("charset") ? tipo : tipo + "; charset=utf-8",
                corpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void enviarBytes(HttpExchange ex, int status, String tipo, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}