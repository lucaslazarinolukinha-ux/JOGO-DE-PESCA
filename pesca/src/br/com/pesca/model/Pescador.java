package br.com.pesca.model;

public class Pescador {
    private String nome;
    private int nivel = 1;
    private int moedas = 0;

    public Pescador(String nome) {
        this.nome = nome;
    }

    public String getNome() {
        return nome;
    }

    public int getNivel() {
        return nivel;
    }

    public int getMoedas() {
        return moedas;
    }

    public void adicionarMoedas(int quantidade) {
        moedas += quantidade;
    }

    public void subirNivel() {
        nivel++;
    }
}