class Pair<U, V>
{
    public final U first;       // first field of a Pair
    public final V second;      // second field of a Pair

    // Constructs a new Pair with specified values
    public Pair(U first, V second)
    {
        this.first = first;
        this.second = second;
    }

    public U getFirst()
    {
        return first;
    }

    public  V getSecond()
    {
        return second;
    }

}