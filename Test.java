    public int numIslands(char[][] grid) {
        int count = 0;
        for (int i = 0; i < grid.size(); i++){
            for (int j = 0; j < grid.get(0).size(); j++){
                if ((i == 0 or grid[i-1][j] == '0') and (j == 0 or grid[i][j-1] == '0')){
                    count++;
                }
            }
        }
        return count;
    }

    public static void main(String[] args) {
        char[][] grid = [
  ["1","1","1","1","0"],
  ["1","1","0","1","0"],
  ["1","1","0","0","0"],
  ["0","0","0","0","0"]];
        System.out.println(new numIslands(grid));
    }
